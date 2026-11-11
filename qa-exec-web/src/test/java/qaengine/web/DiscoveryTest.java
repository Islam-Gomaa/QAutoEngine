package qaengine.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.auth.LocatorSpec.Strategy;
import qaengine.core.auth.LoginForm;
import qaengine.core.discovery.PageInventory;
import qaengine.core.discovery.SafetyClass;
import qaengine.core.discovery.SafetyClassifier;
import qaengine.core.engine.ExplorationEngine;
import qaengine.core.engine.LoginFlow;
import qaengine.core.engine.RunResult;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.Redactor;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionMode;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.RunBudget;
import qaengine.core.policy.ScopedException;
import qaengine.core.policy.TargetScope;
import qaengine.core.verdict.VerdictEngine;
import qaengine.fixtures.FixtureServer;

/** Milestone 6: what discovery finds behind sign-in, judged against what the fixture's console actually holds. */
class DiscoveryTest {

    static FixtureServer fixture;

    @BeforeAll static void start() throws Exception { fixture = FixtureServer.start(); }
    @AfterAll static void stop() { fixture.close(); }
    @BeforeEach void clean() { fixture.clear(); }

    record Discovered(Map<String, PageInventory> pages, RunResult result) {
        PageInventory page(String path) {
            return pages.get(fixture.url(path));
        }
    }

    static Discovered discover(int maxPages, int maxDepth) {
        var login = new ScopedException("login", ScopedException.Purpose.AUTH, fixture.baseUrl(), "POST", "/app/session", null, Instant.now().plusSeconds(600), 5, "test",
                ScopedException.ApproverRole.ORGANIZATION_ADMIN);
        var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, TargetScope.of(fixture.baseUrl()), List.of(login), Set.of(),
                new RunBudget(500, maxPages, maxDepth, Duration.ofMinutes(10), 2));
        var gate = new ActionGate(policy, Clock.systemUTC(), line -> { });
        Map<String, PageInventory> pages = new LinkedHashMap<>();
        try (var b = new PlaywrightBrowser(gate)) {
            var form = new LoginForm(LocatorSpec.of(Strategy.LABEL, "Email"), LocatorSpec.of(Strategy.LABEL, "Password"), LocatorSpec.of(Strategy.TEST_ID, "sign-in"),
                    LocatorSpec.of(Strategy.TEST_ID, "account-menu"), null, 10);
            var spec = new LoginFlow.Spec(LoginFlow.Method.PASSWORD, fixture.baseUrl(), "/app/login", "/app/home", "login", form);
            assertEquals(LoginFlow.Outcome.SIGNED_IN, new LoginFlow(gate, b).attempt(spec, new LoginFlow.Credentials(FixtureServer.TEST_USER, FixtureServer.TEST_PASSWORD, null, null, null), null).outcome());
            fixture.clear();
            var evidence = new EvidenceCollector((type, step, content) -> null, new Redactor(List.of()), 1 << 20);
            RunResult r = new ExplorationEngine(gate, b, new VerdictEngine(policy.scope()), evidence, 11, Clock.systemUTC(), List.of())
                    .sessionEndsAt("/app/login")
                    .onNewState((step, depth, snapshot) -> {
                        PageInventory inventory = b.describePage();
                        if (inventory != null) pages.putIfAbsent(inventory.url(), inventory);
                    })
                    .explore(fixture.url("/app/console"));
            return new Discovered(pages, r);
        }
    }

    static SafetyClass safety(PageInventory page, String name) {
        return page.actions().stream().filter(a -> a.name().equals(name)).map(SafetyClassifier::classify).map(SafetyClassifier.Classified::safety).findFirst()
                .orElseThrow(() -> new AssertionError("no action named " + name + " on " + page.url()));
    }

    @Test void everyPageBehindSignInIsFoundWithItsStructure() {
        var d = discover(50, 10);
        for (String path : List.of("/app/console", "/app/console/users", "/app/console/users/1/edit", "/app/console/billing", "/app/console/settings")) {
            assertTrue(d.pages().containsKey(fixture.url(path)), path);
        }
        var users = d.page("/app/console/users");
        assertEquals("Users", users.title());
        assertEquals(List.of("Users", "Invite a teammate"), users.headings(), "a closed dialog's heading is not on screen");
        assertEquals(List.of(new PageInventory.Navigation("Console", List.of("Console", "Users", "Billing", "Settings"))), users.navigation());
        assertEquals(List.of(new PageInventory.Table("Team members", List.of("Name", "Email", "Role", "Actions"), 3)), users.tables());
        assertEquals(List.of(new PageInventory.Dialog("Remove user", false)), users.dialogs());
        var invite = users.forms().getFirst();
        assertEquals("Invite a teammate", invite.name());
        assertEquals("post", invite.method());
        assertEquals("/app/console/invitations", invite.action());
        assertEquals("Invite user", invite.submitLabel());
        assertEquals(List.of(new PageInventory.Field("Email", "email", "email", true, true), new PageInventory.Field("Role", "role", "select", false, true)), invite.fields());

        var settings = d.page("/app/console/settings").forms().getFirst();
        assertEquals(List.of("Display name", "Email", "Product news", "Timezone", ""), settings.fields().stream().map(PageInventory.Field::label).toList());
        assertEquals(List.of(true, true, false, false, false), settings.fields().stream().map(PageInventory.Field::required).toList());
        assertFalse(settings.fields().getLast().labelled(), "the nickname field has no accessible name, which is itself a finding");
    }

    @Test void everyActionIsClassifiedAndNothingThatChangesStateIsEverUsed() {
        var d = discover(50, 10);
        var users = d.page("/app/console/users");
        assertEquals(SafetyClass.READ_ONLY, safety(users, "Edit"));
        assertEquals(SafetyClass.READ_ONLY, safety(users, "Active"));
        assertEquals(SafetyClass.READ_ONLY, safety(users, "Close"));
        assertEquals(SafetyClass.SENSITIVE, safety(users, "Invite user"));
        assertEquals(SafetyClass.DESTRUCTIVE, safety(users, "Delete user"));
        assertEquals(SafetyClass.DESTRUCTIVE, safety(users, "Remove"), "a link whose address deletes");
        var billing = d.page("/app/console/billing");
        assertEquals(SafetyClass.SENSITIVE, safety(billing, "Pay invoice"));
        assertEquals(SafetyClass.SENSITIVE, safety(billing, "Download invoice"));
        assertEquals(SafetyClass.REVERSIBLE, safety(billing, "Update card"));
        assertEquals(SafetyClass.REVERSIBLE, safety(d.page("/app/console/settings"), "Save changes"));

        for (String never : List.of("/app/console/invitations", "/app/console/payments", "/app/console/users/2/delete", "/app/console/invoices/7.pdf", "/app/console/users/1")) {
            assertFalse(fixture.requests().stream().anyMatch(q -> q.path().equals(never)), never + " was requested");
        }
        assertTrue(fixture.requests().stream().noneMatch(q -> !q.method().equals("GET") && !q.method().equals("HEAD")), "discovery only ever reads");
    }

    @Test void paginationIsRecordedOnceAndNeverFollowedFurther() {
        var d = discover(50, 10);
        assertTrue(d.pages().containsKey(fixture.url("/app/console/users?page=2")));
        assertEquals(2, d.pages().keySet().stream().filter(u -> u.startsWith(fixture.url("/app/console/users")) && !u.contains("/edit")).count(), "page 1 and page 2");
        assertTrue(fixture.requests().stream().filter(q -> q.path().equals("/app/console/users") && q.query() != null).allMatch(q -> q.query().equals("page=2")),
                "no page beyond the one linked");
        assertEquals(RunResult.StopReason.EXHAUSTED, d.result().stop(), "a finite site is explored to the end");
    }

    @Test void theDepthAndPageLimitsHold() {
        var shallow = discover(50, 1);
        assertFalse(shallow.pages().containsKey(fixture.url("/app/console/users/1/edit")), "two links away from the console");
        assertEquals(RunResult.StopReason.DEPTH_LIMIT, shallow.result().stop());
        var small = discover(2, 10);
        assertEquals(2, small.pages().size());
        assertEquals(RunResult.StopReason.BUDGET, small.result().stop());
    }
}
