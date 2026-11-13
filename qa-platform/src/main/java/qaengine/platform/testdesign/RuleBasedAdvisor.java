package qaengine.platform.testdesign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import qaengine.core.script.Script;
import qaengine.core.script.ScriptPlanner;

/**
 * Suggests test cases from discovery by fixed rules, with no model and no network. An expected result is stated only
 * when it was seen (OBSERVED) or follows from the page's own markup (INFERRED, for example a field marked required).
 * Anything that depends on a business rule discovery could not see, such as what submitting a form does, is UNKNOWN
 * and has to be filled in by the reviewer before the case can be approved.
 */
@Component
public class RuleBasedAdvisor implements TestDesignAdvisor {

    /** Enough for a large application's first review; a later run adds what is left, since known keys are skipped. */
    static final int MAX_CANDIDATES = 400;
    static final String READ_ONLY = "READ_ONLY";
    static final String VALIDATION_ONLY = "VALIDATION_ONLY";

    private static final Map<String, String> FORMATS = Map.of("email", "an email address", "url", "a web address", "number", "a number", "tel", "a phone number",
            "date", "a date", "time", "a time", "datetime-local", "a date and time", "month", "a month", "week", "a week");
    private static final Map<String, String> BAD_VALUES = Map.of("email", "\"not-an-email\"", "url", "\"not a web address\"", "number", "\"abc\"", "tel", "\"call me\"",
            "date", "\"31/02/2026\"", "time", "\"25:61\"", "datetime-local", "\"tomorrow\"", "month", "\"13/2026\"", "week", "\"week 60\"");
    private static final Set<String> TEXTUAL = Set.of("text", "email", "url", "tel", "search", "textarea", "password", "number");

    @Override public String name() {
        return "rules";
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static String pageName(Page p) {
        return p.title() == null || p.title().isBlank() ? p.path() : p.title();
    }

    private static String fieldName(Field f) {
        if (f.label() != null && !f.label().isBlank()) return f.label();
        return f.name() == null || f.name().isBlank() ? "unnamed " + f.type() + " field" : f.name();
    }

    private static String formName(Form f, Page p) {
        if (f.name() != null && !f.name().isBlank()) return f.name();
        return "the form" + (f.submitLabel() == null || f.submitLabel().isBlank() ? "" : " sent by " + quote(f.submitLabel())) + " on " + pageName(p);
    }

    private static final Set<String> AREA_PREFIXES = Set.of("app", "admin", "console", "dashboard", "portal");

    /** The application area a path belongs to, for grouping: its first segment past general prefixes such as "app". */
    static String module(String path) {
        List<String> parts = new ArrayList<>();
        for (String s : path.split("[?#]", 2)[0].split("/")) if (!s.isBlank()) parts.add(s);
        int i = 0;
        while (i < parts.size() - 1 && AREA_PREFIXES.contains(parts.get(i).toLowerCase(Locale.ROOT))) i++;
        return parts.isEmpty() ? "home" : parts.get(i);
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static Action submitOf(Page p, Form f) {
        if (f.submitLabel() == null || f.submitLabel().isBlank()) return null;
        return p.actions().stream().filter(a -> a.kind().equals("SUBMIT") && a.name().equals(f.submitLabel())).findFirst().orElse(null);
    }

    private static String safetyNote(Action submit) {
        if (submit == null) return "What sending this form changes was not classified.";
        return switch (submit.safety()) {
            case "DESTRUCTIVE" -> "Sending this form was classified destructive: run it only where removing data is authorized.";
            case "SENSITIVE" -> "Sending this form was classified sensitive: run it only where sending is authorized.";
            case "REVERSIBLE" -> "Sending this form changes data that can be changed back.";
            default -> "Sending this form was classified read-only.";
        };
    }

    private static String priority(Action submit, String otherwise) {
        if (submit == null) return otherwise;
        return switch (submit.safety()) {
            case "DESTRUCTIVE" -> "CRITICAL";
            case "SENSITIVE" -> "HIGH";
            default -> otherwise;
        };
    }

    private final class Builder {
        final Map<String, Candidate> out = new LinkedHashMap<>();
        final boolean signedIn;

        Builder(boolean signedIn) {
            this.signedIn = signedIn;
        }

        List<Step> open(Page p) {
            List<Step> s = new ArrayList<>();
            if (signedIn) s.add(new Step("Sign in with the environment's testing account", "You are signed in", Basis.OBSERVED));
            s.add(new Step("Go to " + p.path(), "The page " + quote(pageName(p)) + " opens", Basis.OBSERVED));
            return s;
        }

        void add(String key, String rule, String title, String description, Page p, String priority, List<String> tags, List<Step> steps,
                 List<String> observed, List<String> inferred, List<String> unknown) {
            add(key, rule, title, description, p, priority, tags, steps, observed, inferred, unknown, Optional.empty(), null, "LOW");
        }

        /**
         * @param script only from the state a page opens in, since a script starts by opening it. The planner builds
         *               it from the same facts the steps above state, and nothing else
         */
        void add(String key, String rule, String title, String description, Page p, String priority, List<String> tags, List<Step> steps,
                 List<String> observed, List<String> inferred, List<String> unknown, Optional<Script> script, String mode, String risk) {
            if (out.size() >= MAX_CANDIDATES || out.containsKey(key)) return;
            Set<String> allTags = new LinkedHashSet<>(List.of("generated"));
            allTags.addAll(tags);
            Script executable = p.onLoad() ? script.orElse(null) : null;
            if (executable != null) allTags.add("executable");
            out.put(key, new Candidate(key, rule, cap(title, 240), description, module(p.path()), priority, List.copyOf(allTags), List.copyOf(steps),
                    List.copyOf(observed), List.copyOf(inferred), List.copyOf(unknown), executable, executable == null ? null : mode, risk,
                    TestDesignAdvisor.confidenceOf(steps)));
        }
    }

    @Override public List<Candidate> suggest(Discovery d) {
        Builder b = new Builder(d.signedIn());
        for (Page p : d.pages()) pageOpens(b, p);
        for (Page p : d.pages()) {
            for (Form f : p.forms()) {
                requiredFields(b, p, f);
                formats(b, p, f);
                validSubmission(b, p, f);
                boundaries(b, p, f);
                accessibleNames(b, p, f);
            }
        }
        for (Page p : d.pages()) riskyActions(b, p);
        for (Page p : d.pages()) tables(b, p);
        for (Page p : d.pages()) navigation(b, p);
        return List.copyOf(b.out.values());
    }

    private void pageOpens(Builder b, Page p) {
        List<Step> steps = b.open(p);
        List<String> observed = new ArrayList<>(List.of("The page answered at " + p.path() + (b.signedIn ? " while signed in" : "")));
        if (!p.headings().isEmpty()) {
            steps.add(new Step("Read the page's headings", "They include " + String.join(", ", p.headings().stream().limit(5).map(RuleBasedAdvisor::quote).toList()), Basis.OBSERVED));
            observed.add("Headings seen: " + String.join(", ", p.headings()));
        }
        b.add("page:" + p.path(), "PAGE_OPENS", "Open " + pageName(p) + (b.signedIn ? " while signed in" : ""), "Checks that the page still opens and shows what discovery saw.",
                p, "MEDIUM", List.of("smoke"), steps, observed, List.of(), List.of(), ScriptPlanner.pageOpens(p.path(), p.title(), p.headings()), READ_ONLY, "LOW");
    }

    private void requiredFields(Builder b, Page p, Form f) {
        Action submit = submitOf(p, f);
        for (Field field : f.fields()) {
            if (!field.required()) continue;
            List<Step> steps = b.open(p);
            List<String> others = f.fields().stream().filter(x -> x.required() && x != field).map(RuleBasedAdvisor::fieldName).toList();
            if (!others.isEmpty()) steps.add(new Step("Fill in " + String.join(", ", others.stream().map(RuleBasedAdvisor::quote).toList()) + " with valid values", "", Basis.OBSERVED));
            steps.add(new Step("Leave " + quote(fieldName(field)) + " empty", "", Basis.OBSERVED));
            steps.add(new Step("Press " + quote(f.submitLabel() == null || f.submitLabel().isBlank() ? "the submit button" : f.submitLabel()),
                    "The form is not sent, and " + quote(fieldName(field)) + " is reported as required", Basis.INFERRED));
            b.add("required:" + p.path() + ":" + formName(f, p) + ":" + fieldName(field), "REQUIRED_FIELD",
                    formName(f, p) + " is not sent without " + fieldName(field), "Negative case. " + safetyNote(submit), p, priority(submit, "HIGH"),
                    List.of("negative", "validation"), steps,
                    List.of(quote(fieldName(field)) + " is marked required in the page"),
                    List.of("An empty required field stops the form from being sent"),
                    List.of("The exact wording of the message is not known"),
                    // Checks the field refuses to be empty, which is what stops the form. Typing, and nothing is sent.
                    field.labelled() ? ScriptPlanner.requiredRefusesEmpty(p.path(), field.label()) : Optional.empty(), VALIDATION_ONLY, "MEDIUM");
        }
    }

    private void formats(Builder b, Page p, Form f) {
        Action submit = submitOf(p, f);
        for (Field field : f.fields()) {
            String format = FORMATS.get(field.type());
            if (format == null) continue;
            List<Step> steps = b.open(p);
            steps.add(new Step("Enter " + BAD_VALUES.get(field.type()) + " in " + quote(fieldName(field)), "", Basis.OBSERVED));
            steps.add(new Step("Press " + quote(f.submitLabel() == null || f.submitLabel().isBlank() ? "the submit button" : f.submitLabel()),
                    "The form is not sent, and " + quote(fieldName(field)) + " is reported as not " + format, Basis.INFERRED));
            b.add("format:" + p.path() + ":" + formName(f, p) + ":" + fieldName(field), "FIELD_FORMAT", fieldName(field) + " in " + formName(f, p) + " refuses a value that is not " + format,
                    "Negative case. " + safetyNote(submit), p, priority(submit, "MEDIUM"), List.of("negative", "validation"), steps,
                    List.of(quote(fieldName(field)) + " is a field of type " + field.type()), List.of("A value that is not " + format + " is refused"), List.of(),
                    field.labelled() ? ScriptPlanner.fieldFormat(p.path(), field.label(), BAD_VALUES.get(field.type()).replace("\"", "")) : Optional.empty(),
                    VALIDATION_ONLY, "MEDIUM");
        }
    }

    private void validSubmission(Builder b, Page p, Form f) {
        Action submit = submitOf(p, f);
        List<Step> steps = b.open(p);
        if (!f.fields().isEmpty()) {
            steps.add(new Step("Fill in " + String.join(", ", f.fields().stream().map(RuleBasedAdvisor::fieldName).map(RuleBasedAdvisor::quote).toList()) + " with valid values", "", Basis.OBSERVED));
        }
        steps.add(new Step("Press " + quote(f.submitLabel() == null || f.submitLabel().isBlank() ? "the submit button" : f.submitLabel()),
                "Not observed: discovery never sends forms. State what should happen, such as a message, the next page, or the saved record.", Basis.UNKNOWN));
        List<String> unknown = new ArrayList<>(List.of("What the application does after the form is sent", "Which values count as valid"));
        if (f.fields().stream().noneMatch(Field::required) && !f.fields().isEmpty()) unknown.add("Which fields are required: none is marked required in the page");
        b.add("submit:" + p.path() + ":" + formName(f, p), "VALID_SUBMISSION", "Send " + formName(f, p) + " with valid values", "Positive case. " + safetyNote(submit),
                p, priority(submit, "HIGH"), List.of("positive", "needs-expected-result"), steps,
                List.of("The form sends " + (f.method() == null ? "GET" : f.method().toUpperCase(Locale.ROOT)) + " to " + (f.action() == null || f.action().isBlank() ? "its own page" : f.action())),
                List.of(), unknown);
    }

    private void boundaries(Builder b, Page p, Form f) {
        List<Field> textual = f.fields().stream().filter(x -> TEXTUAL.contains(x.type())).toList();
        if (textual.isEmpty()) return;
        List<Step> steps = b.open(p);
        for (Field field : textual) {
            steps.add(new Step("Enter the shortest and the longest value " + quote(fieldName(field)) + " should accept, then one character more",
                    "Not stated by the page: confirm the limits for " + quote(fieldName(field)) + ".", Basis.UNKNOWN));
        }
        b.add("boundaries:" + p.path() + ":" + formName(f, p), "BOUNDARIES", "Boundary values for " + formName(f, p),
                "Boundary case. The limits are business rules the page does not state; the reviewer fills them in.", p, "LOW", List.of("boundary", "needs-expected-result"), steps,
                List.of("Fields that take typed values: " + String.join(", ", textual.stream().map(RuleBasedAdvisor::fieldName).toList())), List.of(),
                textual.stream().map(x -> "Length or range limits of " + quote(fieldName(x))).toList());
    }

    private void accessibleNames(Builder b, Page p, Form f) {
        for (Field field : f.fields()) {
            if (field.labelled()) continue;
            List<Step> steps = b.open(p);
            steps.add(new Step("Move to the " + fieldName(field) + " field with a screen reader", "The field is announced by a name that says what to enter", Basis.INFERRED));
            b.add("label:" + p.path() + ":" + formName(f, p) + ":" + fieldName(field), "ACCESSIBLE_NAME", "The " + fieldName(field) + " field in " + formName(f, p) + " has an accessible name",
                    "Accessibility case. Discovery found this field with no accessible name, so it is likely to fail as things are.", p, "MEDIUM",
                    List.of("accessibility", "likely-defect"), steps, List.of("The field had no label or accessible name when discovered"),
                    List.of("Every form field needs an accessible name (WCAG 2.2, 4.1.2)"), List.of());
        }
    }

    private void riskyActions(Builder b, Page p) {
        for (Action a : p.actions()) {
            if (a.kind().equals("SUBMIT")) continue; // covered by the form's own case
            boolean destructive = a.safety().equals("DESTRUCTIVE");
            if (!destructive && !a.safety().equals("SENSITIVE")) continue;
            if (a.name() == null || a.name().isBlank()) continue;
            List<Step> steps = b.open(p);
            steps.add(new Step("Press " + quote(a.name()), destructive
                    ? "Not observed: discovery never uses it. State whether it asks for confirmation and exactly what it removes."
                    : "Not observed: discovery never uses it. State what it should do.", Basis.UNKNOWN));
            if (destructive) steps.add(new Step("Cancel, if asked to confirm", "Not observed: state what must remain unchanged.", Basis.UNKNOWN));
            b.add("action:" + p.path() + ":" + a.name(), destructive ? "DESTRUCTIVE_ACTION" : "SENSITIVE_ACTION",
                    (destructive ? "Removing with " : "Using ") + quote(a.name()) + " on " + pageName(p),
                    (destructive ? "Destructive" : "Sensitive") + " action: run it only where it is authorized, with data that may be changed.", p, destructive ? "CRITICAL" : "HIGH",
                    List.of(destructive ? "destructive" : "sensitive", "requires-authorization", "needs-expected-result"), steps,
                    List.of(quote(a.name()) + " is on the page" + (a.href() == null ? "" : " and leads to " + a.href())),
                    List.of(), List.of("What " + quote(a.name()) + " does, and whether it can be undone"),
                    // Never executable: no policy the engine runs under allows using it.
                    Optional.empty(), null, "HIGH");
        }
    }

    private void tables(Builder b, Page p) {
        for (Table t : p.tables()) {
            if (t.headers().isEmpty()) continue;
            String name = t.caption() == null || t.caption().isBlank() ? "The table on " + pageName(p) : quote(t.caption()) + " table";
            List<Step> steps = b.open(p);
            steps.add(new Step("Look at " + name, "It has the columns " + String.join(", ", t.headers().stream().map(RuleBasedAdvisor::quote).toList()), Basis.OBSERVED));
            b.add("table:" + p.path() + ":" + name, "TABLE_COLUMNS", name + " shows its columns", "Checks that the table keeps the columns discovery saw.", p, "LOW", List.of("content"), steps,
                    List.of("Columns seen: " + String.join(", ", t.headers()), t.rows() + " rows when discovered"), List.of(), List.of("Which rows should appear for the testing account"),
                    ScriptPlanner.tableColumns(p.path(), t.headers(), t.rows()), READ_ONLY, "LOW");
        }
    }

    private void navigation(Builder b, Page p) {
        for (Link l : p.links()) {
            if (l.via() == null || l.via().isBlank() || l.toPath().equals(p.path())) continue;
            List<Step> steps = b.open(p);
            String to = l.toTitle() == null || l.toTitle().isBlank() ? l.toPath() : l.toTitle();
            steps.add(new Step("Use " + quote(l.via()), "The page " + quote(to) + " opens at " + l.toPath(), Basis.OBSERVED));
            // A link can be recorded from a page while it was only on screen after something was used, such as a tab.
            // A script opens the page and uses the link at once, so it is planned only for a link the page shows as it opens.
            boolean onScreen = p.actions().stream().anyMatch(a -> "LINK".equals(a.kind()) && l.via().strip().equals(a.name() == null ? null : a.name().strip()));
            b.add("nav:" + p.path() + "->" + l.toPath(), "NAVIGATION", "From " + pageName(p) + " to " + to + " by " + quote(l.via()), "Checks a way through the application that discovery took.",
                    p, "LOW", List.of("navigation"), steps, List.of(quote(l.via()) + " led from " + p.path() + " to " + l.toPath()), List.of(), List.of(),
                    onScreen ? ScriptPlanner.navigation(p.path(), l.via(), l.toPath(), null) : Optional.empty(), READ_ONLY, "LOW");
        }
    }
}
