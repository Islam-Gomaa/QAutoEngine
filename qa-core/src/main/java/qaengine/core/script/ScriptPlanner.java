package qaengine.core.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.policy.PolicyException;

/**
 * Builds executable test cases from what discovery saw, and from nothing else: every element a script uses or reads
 * was found on that page, by its role and accessible name or its label. There is no knowledge of any particular
 * application here. A fact discovery could not see, such as what a form does when it is sent, never becomes a step.
 *
 * <p>Each method returns empty when the facts are not enough for a script that makes at least one real expectation.
 */
public final class ScriptPlanner {

    /** Enough to show a table kept its shape, few enough that one renamed column is easy to see. */
    static final int MAX_COLUMNS = 8;

    private ScriptPlanner() {}

    private static boolean usable(String s) {
        return s != null && !s.isBlank() && s.length() <= 200 && !s.chars().anyMatch(Character::isISOControl);
    }

    private static LocatorSpec role(String role, String name) {
        return new LocatorSpec(LocatorSpec.Strategy.ROLE, role, name);
    }

    private static Optional<Script> script(List<ScriptStep> steps) {
        try {
            return Optional.of(new Script(steps).validated());
        } catch (PolicyException e) {
            return Optional.empty(); // A fact that cannot become a valid step leaves the case for a person.
        }
    }

    /** The page still opens, keeps its title, and shows its main heading. */
    public static Optional<Script> pageOpens(String path, String title, List<String> headings) {
        List<ScriptStep> steps = new ArrayList<>(List.of(ScriptStep.open(path)));
        if (usable(title)) steps.add(ScriptStep.expectTitle(title.strip()));
        String heading = headings == null ? null : headings.stream().filter(ScriptPlanner::usable).findFirst().orElse(null);
        if (heading != null) steps.add(ScriptStep.expectVisible(role("heading", heading.strip())));
        return steps.size() > 1 ? script(steps) : Optional.empty();
    }

    /** A link discovery followed still leads where it led: same path, and the heading that page showed. */
    public static Optional<Script> navigation(String fromPath, String linkName, String toPath, String toHeading) {
        if (!usable(linkName)) return Optional.empty();
        List<ScriptStep> steps = new ArrayList<>(List.of(ScriptStep.open(fromPath), ScriptStep.activate(role("link", linkName.strip())), ScriptStep.expectUrl(toPath)));
        if (usable(toHeading)) steps.add(ScriptStep.expectVisible(role("heading", toHeading.strip())));
        return script(steps);
    }

    /** A table keeps the columns discovery saw, and still has rows if it had them. */
    public static Optional<Script> tableColumns(String path, List<String> headers, int rows) {
        List<ScriptStep> steps = new ArrayList<>(List.of(ScriptStep.open(path)));
        if (headers != null) headers.stream().filter(ScriptPlanner::usable).limit(MAX_COLUMNS)
                .forEach(h -> steps.add(ScriptStep.expectVisible(role("columnheader", h.strip()))));
        // A header row plus at least one row of data. Discovery saw rows; their content is the application's own data.
        if (rows > 0) steps.add(ScriptStep.expectMinCount(role("row", null), 2));
        return steps.size() > 1 ? script(steps) : Optional.empty();
    }

    /**
     * A field discovery saw marked required is still marked required. Read from the page, with nothing typed: a field
     * may load with a value already in it, so "it is invalid now" would be a guess. That needs {@link #requiredRefusesEmpty}.
     */
    public static Optional<Script> requiredField(String path, String label) {
        if (!usable(label)) return Optional.empty();
        LocatorSpec field = new LocatorSpec(LocatorSpec.Strategy.LABEL, label.strip(), null);
        return script(List.of(ScriptStep.open(path), ScriptStep.expectState(field, "required")));
    }

    /** A required field, emptied, refuses to stay empty. Typing, so VALIDATION_ONLY; READ_ONLY reports it BLOCKED. Nothing is sent. */
    public static Optional<Script> requiredRefusesEmpty(String path, String label) {
        if (!usable(label)) return Optional.empty();
        LocatorSpec field = new LocatorSpec(LocatorSpec.Strategy.LABEL, label.strip(), null);
        return script(List.of(ScriptStep.open(path), ScriptStep.fill(field, ""), ScriptStep.expectInvalid(field)));
    }

    /**
     * A typed field refuses a value of the wrong kind. Typing is allowed only in VALIDATION_ONLY; under READ_ONLY the
     * gate refuses the step and the case reports BLOCKED, which is the truth about that environment.
     */
    public static Optional<Script> fieldFormat(String path, String label, String badValue) {
        if (!usable(label) || !usable(badValue)) return Optional.empty();
        LocatorSpec field = new LocatorSpec(LocatorSpec.Strategy.LABEL, label.strip(), null);
        return script(List.of(ScriptStep.open(path), ScriptStep.fill(field, badValue), ScriptStep.expectInvalid(field)));
    }

    /** A tab discovery found still opens in place and becomes the selected one. */
    public static Optional<Script> tab(String path, String tabName) {
        if (!usable(tabName)) return Optional.empty();
        LocatorSpec tab = role("tab", tabName.strip());
        return script(List.of(ScriptStep.open(path), ScriptStep.activate(tab), ScriptStep.expectState(tab, "selected")));
    }
}
