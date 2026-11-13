package qaengine.core.verdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import qaengine.core.model.Navigation;
import qaengine.core.model.Signal;
import qaengine.core.policy.TargetScope;

/**
 * Turns observations into verdicts using fixed, explainable rules. Same input, same output: no
 * randomness and no learned state. Target content can never change a rule.
 */
public final class VerdictEngine {

    private static final Set<String> IMAGE_LIKE = Set.of("image", "media", "font");

    private final TargetScope firstParty;
    private final Predicate<Signal> noise;

    public VerdictEngine(TargetScope firstParty) {
        this(firstParty, s -> false);
    }

    /** @param noise per-target rule marking known noise, in addition to browser housekeeping */
    public VerdictEngine(TargetScope firstParty, Predicate<Signal> noise) {
        this.firstParty = firstParty;
        this.noise = noise;
    }

    public StepEvaluation evaluate(Navigation navigation, ExpectedOutcome expected, boolean stateChanged, List<Signal> signals) {
        if (navigation.outcome() == Navigation.Outcome.BLOCKED) {
            return new StepEvaluation(Verdict.BLOCKED, List.of(), navigation.detail());
        }
        if (navigation.outcome() == Navigation.Outcome.REDIRECTED) {
            return new StepEvaluation(Verdict.INCONCLUSIVE, List.of(), "redirect returned to the gate: " + navigation.redirectTarget());
        }

        boolean policyInterfered = signals.stream().anyMatch(s -> s.type() == Signal.Type.BLOCKED_BY_POLICY);
        Map<String, Finding> findings = new LinkedHashMap<>();

        for (Signal s : signals) {
            if (noise.test(s) || isHousekeeping(s)) continue;
            switch (s.type()) {
                case RESPONSE -> response(s).ifPresent(f -> findings.putIfAbsent(f.fingerprint(), f));
                case PAGE_EXCEPTION -> {
                    if (!policyInterfered) add(findings, Finding.of(Finding.Type.SCRIPT_ERROR, Severity.MEDIUM, s.url(), "Uncaught script exception", firstLine(s.message())));
                }
                case CONSOLE_ERROR -> {
                    if (!policyInterfered) add(findings, Finding.of(Finding.Type.CONSOLE_ERROR, Severity.LOW, s.url(), "Console error", firstLine(s.message())));
                }
                default -> { }
            }
        }

        if (navigation.outcome() == Navigation.Outcome.FAILED) {
            add(findings, Finding.of(Finding.Type.NAVIGATION_FAILURE, Severity.MEDIUM, navigation.url(), "Page could not be loaded", navigation.detail()));
        }

        List<Finding> list = new ArrayList<>(findings.values());
        if (!list.isEmpty()) return new StepEvaluation(Verdict.FAIL, list, "");
        if (policyInterfered) return new StepEvaluation(Verdict.INCONCLUSIVE, List.of(), "policy blocked part of this page");

        return switch (expected) {
            case PAGE_LOADS -> {
                int status = navigation.status();
                if (status >= 200 && status < 300) yield new StepEvaluation(Verdict.PASS, List.of(), "");
                if (status == 401 || status == 403) yield new StepEvaluation(Verdict.INCONCLUSIVE, List.of(), "protected: " + status);
                yield new StepEvaluation(Verdict.INCONCLUSIVE, List.of(), "status " + status);
            }
            case STATE_CHANGES -> stateChanged
                    ? new StepEvaluation(Verdict.PASS, List.of(), "")
                    : new StepEvaluation(Verdict.INCONCLUSIVE, List.of(), "action had no observable effect");
        };
    }

    private static void add(Map<String, Finding> findings, Finding f) {
        findings.putIfAbsent(f.fingerprint(), f);
    }

    private java.util.Optional<Finding> response(Signal s) {
        int status = s.status();
        if (status < 400 || !firstParty.allowsHost(s.url())) return java.util.Optional.empty();
        if (status == 401 || status == 403 || status == 405 || status == 429) return java.util.Optional.empty();
        String detail = "HTTP " + status;
        if (s.isDocument()) {
            return java.util.Optional.of(status >= 500
                    ? Finding.of(Finding.Type.SERVER_ERROR, Severity.HIGH, s.url(), "Page returned a server error", detail)
                    : Finding.of(Finding.Type.BROKEN_PAGE, Severity.MEDIUM, s.url(), "Page not found", detail));
        }
        if (IMAGE_LIKE.contains(s.resource())) {
            return java.util.Optional.of(Finding.of(Finding.Type.BROKEN_RESOURCE, Severity.LOW, s.url(), "Broken resource", detail));
        }
        return java.util.Optional.of(Finding.of(Finding.Type.FAILED_REQUEST, status >= 500 ? Severity.HIGH : Severity.MEDIUM, s.url(), "Request failed", detail));
    }

    private static boolean isHousekeeping(Signal s) {
        return s.url() != null && s.url().endsWith("/favicon.ico");
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}
