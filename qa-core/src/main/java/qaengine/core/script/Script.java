package qaengine.core.script;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.policy.PolicyException;

/**
 * An executable test case: what to do and what must then be true. Validated before it is saved, before it is sent
 * to a worker, and again before it runs, so neither a person, a rule nor a model can hand the runner something it
 * would have to guess about. Validity says nothing about permission: every action is still decided by the gate.
 */
public record Script(List<ScriptStep> steps) {

    public static final int MAX_STEPS = 40;
    /** A path on the environment's own origin, with an optional query. No scheme, no host, no fragment, no "//". */
    private static final Pattern PATH = Pattern.compile("/(?!/)[A-Za-z0-9._~!$&'()*+,;=:@%/-]{0,250}(\\?[A-Za-z0-9._~!$&'()*+,;=:@%/?-]{0,250})?");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    static final Set<String> STATES = Set.of("selected", "expanded", "required");

    public Script {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static Script of(ScriptStep... steps) {
        return new Script(List.of(steps)).validated();
    }

    /** Throws {@link PolicyException} with a fixed message naming the step, never repeating its content. */
    public Script validated() {
        if (steps.isEmpty() || steps.size() > MAX_STEPS) throw new PolicyException("A script has 1 to " + MAX_STEPS + " steps");
        if (steps.getFirst() == null || steps.getFirst().op() != ScriptStep.Op.OPEN) throw new PolicyException("A script starts by opening a page");
        if (steps.stream().noneMatch(s -> s != null && s.op() != null && s.op().isExpectation())) {
            throw new PolicyException("A script needs at least one expectation: opening pages proves nothing on its own");
        }
        for (int i = 0; i < steps.size(); i++) check(i + 1, steps.get(i));
        return this;
    }

    private static void check(int n, ScriptStep s) {
        if (s == null || s.op() == null) throw new PolicyException("Step " + n + " has no operation");
        boolean needsTarget = switch (s.op()) {
            case ACTIVATE, FILL, EXPECT_VISIBLE, EXPECT_MIN_COUNT, EXPECT_INVALID, EXPECT_STATE -> true;
            default -> false;
        };
        if (needsTarget && s.target() == null) throw new PolicyException("Step " + n + " needs an element");
        if (s.target() != null) {
            // Rebuilding runs the locator's own checks, whichever way it was deserialized.
            new LocatorSpec(s.target().strategy(), s.target().value(), s.target().name());
        }
        switch (s.op()) {
            case OPEN -> {
                if (s.path() == null || !PATH.matcher(s.path()).matches()) throw new PolicyException("Step " + n + " opens a path on the environment, such as /orders");
            }
            case FILL -> text(n, s.value(), true);
            case EXPECT_URL -> {
                if (s.value() == null || !PATH.matcher(s.value()).matches()) throw new PolicyException("Step " + n + " expects a path, such as /orders");
            }
            case EXPECT_TITLE, EXPECT_TEXT -> text(n, s.value(), false);
            case EXPECT_MIN_COUNT -> {
                if (s.count() == null || s.count() < 1 || s.count() > 1000) throw new PolicyException("Step " + n + " expects a count from 1 to 1000");
            }
            case EXPECT_STATE -> {
                if (s.value() == null || !STATES.contains(s.value())) throw new PolicyException("Step " + n + " expects a state: selected, expanded or required");
            }
            default -> { }
        }
        if (s.op() != ScriptStep.Op.OPEN && s.path() != null) throw new PolicyException("Step " + n + " has a path but does not open a page");
    }

    private static void text(int n, String value, boolean emptyAllowed) {
        if (value == null || (!emptyAllowed && value.isBlank()) || value.length() > 200 || CONTROL.matcher(value).find()) {
            throw new PolicyException("Step " + n + " needs text of up to 200 characters");
        }
    }

    public int expectations() {
        return (int) steps.stream().filter(s -> s.op().isExpectation()).count();
    }
}
