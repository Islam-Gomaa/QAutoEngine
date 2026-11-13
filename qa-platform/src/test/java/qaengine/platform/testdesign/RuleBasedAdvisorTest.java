package qaengine.platform.testdesign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import qaengine.platform.testdesign.TestDesignAdvisor.Action;
import qaengine.platform.testdesign.TestDesignAdvisor.Basis;
import qaengine.platform.testdesign.TestDesignAdvisor.Candidate;
import qaengine.platform.testdesign.TestDesignAdvisor.Discovery;
import qaengine.platform.testdesign.TestDesignAdvisor.Field;
import qaengine.platform.testdesign.TestDesignAdvisor.Form;
import qaengine.platform.testdesign.TestDesignAdvisor.Link;
import qaengine.platform.testdesign.TestDesignAdvisor.Page;
import qaengine.platform.testdesign.TestDesignAdvisor.Table;

/** Milestone 7: what the rules suggest from the fixture console's discovered pages, and what they refuse to claim. */
class RuleBasedAdvisorTest {

    static final Page USERS = new Page("/app/console/users", "Users", List.of("Users", "Invite a teammate"),
            List.of(new Form("Invite a teammate", "post", "/app/console/invitations", List.of(new Field("Email", "email", "email", true, true), new Field("Role", "role", "select", false, true)), "Invite user")),
            List.of(new Table("Team members", List.of("Name", "Email", "Role", "Actions"), 3)),
            List.of(new Action("LINK", "Edit", "http://f/app/console/users/1/edit", "READ_ONLY"), new Action("LINK", "Remove", "http://f/app/console/users/2/delete", "DESTRUCTIVE"),
                    new Action("BUTTON", "Delete user", null, "DESTRUCTIVE"), new Action("SUBMIT", "Invite user", null, "SENSITIVE")),
            List.of(new Link("/app/console/users/1/edit", "Edit user", "Edit")));
    static final Page SETTINGS = new Page("/app/console/settings", "Settings", List.of("Settings"),
            List.of(new Form("Profile", "post", "/app/console/settings", List.of(new Field("Display name", "display_name", "text", true, true), new Field("Email", "email", "email", true, true),
                    new Field("Product news", "news", "checkbox", false, true), new Field("", "nickname", "text", false, false)), "Save changes")),
            List.of(), List.of(new Action("SUBMIT", "Save changes", null, "REVERSIBLE")), List.of());
    static final Page BILLING = new Page("/app/console/billing", "Billing", List.of("Billing"), List.of(), List.of(),
            List.of(new Action("LINK", "Download invoice", "http://f/app/console/invoices/7.pdf", "SENSITIVE"), new Action("BUTTON", "Update card", null, "REVERSIBLE")), List.of());

    static List<Candidate> suggest(boolean signedIn, Page... pages) {
        return new RuleBasedAdvisor().suggest(new Discovery(signedIn, List.of(pages)));
    }

    static Candidate find(List<Candidate> all, String rule, String titlePart) {
        return all.stream().filter(c -> c.rule().equals(rule) && c.title().contains(titlePart)).findFirst()
                .orElseThrow(() -> new AssertionError(rule + " " + titlePart + " not in " + all.stream().map(Candidate::title).toList()));
    }

    @Test void positiveNegativeBoundaryAndRiskCasesAreSuggestedFromWhatWasFound() {
        var all = suggest(true, USERS, SETTINGS, BILLING);
        assertEquals(Set.of("PAGE_OPENS", "REQUIRED_FIELD", "FIELD_FORMAT", "VALID_SUBMISSION", "BOUNDARIES", "ACCESSIBLE_NAME", "DESTRUCTIVE_ACTION", "SENSITIVE_ACTION",
                "TABLE_COLUMNS", "NAVIGATION"), all.stream().map(Candidate::rule).collect(Collectors.toSet()));

        var required = find(all, "REQUIRED_FIELD", "without Email");
        assertEquals("Invite a teammate is not sent without Email", required.title());
        assertEquals("HIGH", required.priority(), "sending invitations is sensitive");
        assertEquals(Basis.INFERRED, required.steps().getLast().basis(), "required is read from the page, not seen enforced");
        assertTrue(required.tags().containsAll(List.of("generated", "negative", "validation")));
        assertEquals("users", required.module());

        assertEquals(2, all.stream().filter(c -> c.rule().equals("REQUIRED_FIELD") && c.title().startsWith("Profile")).count(), "Display name and Email, not the optional ones");
        assertTrue(find(all, "REQUIRED_FIELD", "without Display name").steps().stream().anyMatch(s -> s.action().equals("Fill in \"Email\" with valid values")),
                "the other required field is filled in");

        var format = find(all, "FIELD_FORMAT", "Email in Invite a teammate");
        assertTrue(format.steps().stream().anyMatch(s -> s.action().contains("not-an-email")));

        var label = find(all, "ACCESSIBLE_NAME", "nickname");
        assertTrue(label.tags().contains("likely-defect"));

        var delete = find(all, "DESTRUCTIVE_ACTION", "\"Delete user\"");
        assertEquals("CRITICAL", delete.priority());
        assertTrue(delete.tags().contains("requires-authorization"));
        find(all, "DESTRUCTIVE_ACTION", "\"Remove\"");
        find(all, "SENSITIVE_ACTION", "\"Download invoice\"");
        assertFalse(all.stream().anyMatch(c -> c.title().contains("Update card")), "a reversible button gets no risk case of its own");

        assertEquals("From Users to Edit user by \"Edit\"", find(all, "NAVIGATION", "Edit user").title());
        assertTrue(find(all, "TABLE_COLUMNS", "Team members").steps().getLast().expected().contains("\"Role\""));
    }

    @Test void whatWasNotSeenIsLeftUnknownNeverInvented() {
        var all = suggest(true, USERS, SETTINGS, BILLING);
        var submit = find(all, "VALID_SUBMISSION", "Invite a teammate");
        assertEquals(Basis.UNKNOWN, submit.steps().getLast().basis());
        assertTrue(submit.steps().getLast().expected().startsWith("Not observed"));
        assertTrue(submit.unknown().contains("What the application does after the form is sent"));
        for (Candidate c : all.stream().filter(c -> Set.of("VALID_SUBMISSION", "BOUNDARIES", "DESTRUCTIVE_ACTION", "SENSITIVE_ACTION").contains(c.rule())).toList()) {
            assertTrue(c.steps().stream().anyMatch(s -> s.basis() == Basis.UNKNOWN), c.title());
            assertTrue(c.tags().contains("needs-expected-result"), c.title());
        }
        for (Candidate c : all) {
            for (var s : c.steps()) {
                if (s.basis() == Basis.UNKNOWN) assertTrue(s.expected().startsWith("Not observed") || s.expected().startsWith("Not stated"), c.title() + ": " + s.expected());
            }
            assertFalse(c.observed().isEmpty(), "every case says what it rests on: " + c.title());
        }
        var boundaries = find(all, "BOUNDARIES", "Profile");
        assertEquals(3, boundaries.steps().stream().filter(s -> s.basis() == Basis.UNKNOWN).count(), "display name, email and the unnamed text field; not the checkbox");
    }

    @Test void theSameDiscoveryGivesTheSameCasesAndKeysAreStable() {
        var first = suggest(true, USERS, SETTINGS, BILLING);
        var second = suggest(true, USERS, SETTINGS, BILLING);
        assertEquals(first, second);
        assertEquals(first.size(), first.stream().map(Candidate::key).distinct().count());
    }

    @Test void withoutSignInNoCaseClaimsOne() {
        var all = suggest(false, BILLING);
        assertTrue(all.stream().flatMap(c -> c.steps().stream()).noneMatch(s -> s.action().startsWith("Sign in")));
        assertTrue(suggest(true, BILLING).getFirst().steps().getFirst().action().startsWith("Sign in"));
    }

    @Test void modulesGroupByApplicationArea() {
        assertEquals("users", RuleBasedAdvisor.module("/app/console/users/1/edit"));
        assertEquals("console", RuleBasedAdvisor.module("/app/console"));
        assertEquals("orders", RuleBasedAdvisor.module("/orders?page=2"));
        assertEquals("home", RuleBasedAdvisor.module("/"));
    }

    @Test void theNumberOfSuggestionsIsBounded() {
        List<Page> many = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) many.add(new Page("/p/" + i, "P" + i, List.of(), List.of(), List.of(), List.of(), List.of()));
        assertEquals(RuleBasedAdvisor.MAX_CANDIDATES, suggest(true, many.toArray(Page[]::new)).size());
    }
}
