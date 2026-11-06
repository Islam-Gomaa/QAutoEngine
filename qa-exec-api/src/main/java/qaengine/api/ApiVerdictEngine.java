package qaengine.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import qaengine.core.verdict.Finding;
import qaengine.core.verdict.Severity;
import qaengine.core.verdict.StepEvaluation;
import qaengine.core.verdict.Verdict;

/**
 * Fixed rules from scenario and response to verdict. No randomness, no learned state and no AI:
 * the same exchange always gets the same result.
 */
public final class ApiVerdictEngine {

    private static final Pattern LEAK = Pattern.compile(
            "(\\bat [\\w.$]+\\([\\w.]+\\.java:\\d+\\))|(\\w+\\.java:\\d+)|(Traceback \\(most recent call last\\))|(java\\.lang\\.\\w+Exception)"
                    + "|(SQLSTATE\\[)|(ORA-\\d{5})|(syntax error at or near)|(/usr/local/lib/python)|(Fatal error:.* on line \\d+)");

    private final long slowLimitMillis;

    public ApiVerdictEngine(long slowLimitMillis) {
        this.slowLimitMillis = slowLimitMillis;
    }

    public StepEvaluation evaluate(ApiScenario s, ApiClient.Exchange x) {
        if (x.outcome() == ApiClient.Exchange.Outcome.BLOCKED) return new StepEvaluation(Verdict.BLOCKED, List.of(), x.detail());
        if (x.outcome() == ApiClient.Exchange.Outcome.FAILED) return new StepEvaluation(Verdict.INCONCLUSIVE, List.of(), "no response: " + x.detail());

        List<Finding> findings = new ArrayList<>();
        ApiOperation op = s.operation();
        String where = path(x.url());
        int status = x.status();
        leak(findings, s, x, where);

        if (status == 429) return new StepEvaluation(Verdict.INCONCLUSIVE, findings, "rate limited");
        if (status >= 500) {
            findings.add(Finding.of(Finding.Type.API_SERVER_ERROR, Severity.HIGH, where, "API server error", "HTTP " + status + " for " + s.kind()));
            return result(findings, "");
        }
        switch (s.kind()) {
            case OPTIONS_PROBE -> { return status < 400 || status == 405 ? pass(findings) : new StepEvaluation(Verdict.INCONCLUSIVE, findings, "OPTIONS returned " + status); }
            case HEAD_PARITY -> { return status < 400 || status == 405 ? pass(findings) : new StepEvaluation(Verdict.INCONCLUSIVE, findings, "HEAD returned " + status); }
            case NO_CREDENTIAL, INVALID_CREDENTIAL -> {
                if (status >= 200 && status < 300) {
                    findings.add(Finding.of(Finding.Type.API_AUTH_BYPASS, Severity.CRITICAL, where, "Protected API works without valid credentials", "Returned HTTP " + status));
                    return result(findings, "");
                }
                return status == 401 || status == 403 ? pass(findings) : new StepEvaluation(Verdict.INCONCLUSIVE, findings, "unexpected status " + status);
            }
            case LOW_PRIVILEGE -> {
                if (status >= 200 && status < 300) {
                    findings.add(Finding.of(Finding.Type.API_AUTHORIZATION, Severity.HIGH, where, "Low-privilege credential was allowed", "Requires " + op.requiredRole() + ", returned HTTP " + status));
                    return result(findings, "");
                }
                return status == 403 ? pass(findings) : new StepEvaluation(Verdict.INCONCLUSIVE, findings, "unexpected status " + status);
            }
            case UNDOCUMENTED -> { return generic(findings, x, where); }
            default -> { }
        }
        if (s.kind().expectsRejection()) {
            if (status >= 200 && status < 300) {
                findings.add(Finding.of(Finding.Type.API_INPUT_VALIDATION, Severity.MEDIUM, where, "Invalid input was accepted", s.kind() + " returned HTTP " + status));
                return result(findings, "");
            }
            if (status >= 400) contract(findings, op, x, where);
            return status >= 400 ? result(findings, "") : new StepEvaluation(Verdict.INCONCLUSIVE, findings, "status " + status);
        }
        if (status == 401 || status == 403) return new StepEvaluation(Verdict.INCONCLUSIVE, findings, "protected: " + status);
        contract(findings, op, x, where);
        headers(findings, x, where);
        return result(findings, "");
    }

    /** One rule for speed, so a slow endpoint is reported once from a measured median. */
    public Finding slow(ApiOperation op, String where, long medianMillis) {
        return Finding.of(Finding.Type.API_SLOW, Severity.LOW, where, "API response is slow", op.label() + " over " + slowLimitMillis + " ms");
    }

    public boolean isSlow(long medianMillis) {
        return medianMillis > slowLimitMillis;
    }

    private static void leak(List<Finding> findings, ApiScenario s, ApiClient.Exchange x, String where) {
        if (LEAK.matcher(x.body()).find()) {
            findings.add(Finding.of(Finding.Type.API_INFO_LEAK, Severity.MEDIUM, where, "Response exposes internal details", "HTTP " + x.status() + " body contains implementation details"));
        }
    }

    private static void contract(List<Finding> findings, ApiOperation op, ApiClient.Exchange x, String where) {
        int status = x.status();
        if (!op.declares(status)) {
            findings.add(Finding.of(Finding.Type.API_UNDECLARED_STATUS, Severity.MEDIUM, where, "Status not declared by the API contract", op.label() + " returned HTTP " + status));
            return;
        }
        ApiOperation.Response declared = op.responseFor(status);
        if (declared == null || declared.mediaType() == null) return;
        String type = x.header("content-type");
        String actual = type == null ? "" : type.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!actual.equals(declared.mediaType().toLowerCase(Locale.ROOT))) {
            findings.add(Finding.of(Finding.Type.API_CONTENT_TYPE, Severity.MEDIUM, where, "Wrong content type", "Declared " + declared.mediaType() + ", returned " + (actual.isEmpty() ? "none" : actual)));
            return;
        }
        if (declared.schema() == null || declared.schema().isMissingNode() || declared.schema().isEmpty()) return;
        var document = SchemaChecker.parse(x.body());
        if (document.isEmpty()) {
            findings.add(Finding.of(Finding.Type.API_SCHEMA, Severity.MEDIUM, where, "Response is not valid JSON", op.label()));
            return;
        }
        var result = SchemaChecker.check(declared.schema(), document.get());
        if (!result.valid() && !result.unusable()) {
            findings.add(Finding.of(Finding.Type.API_SCHEMA, Severity.MEDIUM, where, "Response does not match the schema", result.detail()));
        }
    }

    private static void headers(List<Finding> findings, ApiClient.Exchange x, String where) {
        if (x.status() >= 200 && x.status() < 300 && x.header("x-content-type-options") == null) {
            findings.add(Finding.of(Finding.Type.API_HEADERS, Severity.LOW, where, "Missing security header", "X-Content-Type-Options"));
        }
    }

    /** Without a contract, only universal rules apply. */
    private static StepEvaluation generic(List<Finding> findings, ApiClient.Exchange x, String where) {
        String type = x.header("content-type");
        if (x.status() < 300 && type != null && type.contains("json") && SchemaChecker.parse(x.body()).isEmpty()) {
            findings.add(Finding.of(Finding.Type.API_SCHEMA, Severity.MEDIUM, where, "Response is not valid JSON", "Content type says JSON"));
        }
        if (findings.isEmpty() && x.status() >= 400) return new StepEvaluation(Verdict.INCONCLUSIVE, List.of(), "no contract for status " + x.status());
        return result(findings, "");
    }

    private static StepEvaluation pass(List<Finding> findings) {
        return result(findings, "");
    }

    private static StepEvaluation result(List<Finding> findings, String note) {
        return new StepEvaluation(findings.isEmpty() ? Verdict.PASS : Verdict.FAIL, findings, note);
    }

    private static String path(String url) {
        return url.replaceAll("\\?.*$", "");
    }
}
