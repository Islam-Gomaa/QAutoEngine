package qaengine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import qaengine.api.ApiClient.Exchange;
import qaengine.api.ApiScenario.Credential;
import qaengine.api.ApiScenario.Kind;
import qaengine.core.verdict.Finding;
import qaengine.core.verdict.Severity;
import qaengine.core.verdict.StepEvaluation;
import qaengine.core.verdict.Verdict;

class ApiVerdictEngineTest {

    static final String SPEC = "{\"openapi\":\"3.0.0\",\"paths\":{\"/a\":{\"get\":{\"security\":[{\"b\":[]}],\"x-requires-role\":\"admin\",\"responses\":{"
            + "\"200\":{\"description\":\"\",\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"required\":[\"id\"],\"properties\":{\"id\":{\"type\":\"integer\"}}}}}},"
            + "\"400\":{\"description\":\"\"},\"401\":{\"description\":\"\"},\"403\":{\"description\":\"\"}}}}}}";
    static final ApiOperation OP = OpenApiReader.read(SPEC).getFirst();
    final ApiVerdictEngine engine = new ApiVerdictEngine(1000);

    static ApiScenario scenario(Kind k) {
        return new ApiScenario(k, OP, Map.of(), Map.of(), Credential.PRIMARY);
    }

    static Exchange response(int status, String type, String body) {
        return new Exchange("GET", "http://t/a", status, Map.of("content-type", type, "x-content-type-options", "nosniff"), body, 5, Exchange.Outcome.RESPONDED, "");
    }

    StepEvaluation eval(Kind k, int status, String body) {
        return engine.evaluate(scenario(k), response(status, "application/json", body));
    }

    Finding.Type type(StepEvaluation e) {
        return e.findings().getFirst().type();
    }

    @Test void goodResponsePasses() {
        assertEquals(Verdict.PASS, eval(Kind.POSITIVE, 200, "{\"id\":1}").verdict());
    }

    @Test void schemaViolationFails() {
        assertEquals(Finding.Type.API_SCHEMA, type(eval(Kind.POSITIVE, 200, "{\"id\":\"1\"}")));
        assertEquals(Finding.Type.API_SCHEMA, type(eval(Kind.POSITIVE, 200, "{}")));
        assertEquals(Finding.Type.API_SCHEMA, type(eval(Kind.POSITIVE, 200, "not json")));
    }

    @Test void wrongContentTypeFails() {
        assertEquals(Finding.Type.API_CONTENT_TYPE, type(engine.evaluate(scenario(Kind.POSITIVE), response(200, "text/html", "<p>"))));
        assertEquals(Verdict.PASS, engine.evaluate(scenario(Kind.POSITIVE), response(200, "application/json; charset=utf-8", "{\"id\":1}")).verdict());
    }

    @Test void undeclaredStatusFails() {
        assertEquals(Finding.Type.API_UNDECLARED_STATUS, type(eval(Kind.POSITIVE, 202, "{}")));
    }

    @Test void missingHeaderIsLow() {
        var x = new Exchange("GET", "http://t/a", 200, Map.of("content-type", "application/json"), "{\"id\":1}", 5, Exchange.Outcome.RESPONDED, "");
        var e = engine.evaluate(scenario(Kind.POSITIVE), x);
        assertEquals(Finding.Type.API_HEADERS, type(e));
        assertEquals(Severity.LOW, e.findings().getFirst().severity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_TYPE", "MISSING_REQUIRED", "BELOW_MINIMUM", "ABOVE_MAXIMUM", "INVALID_ENUM"})
    void invalidInputMustBeRejected(String kind) {
        assertEquals(Finding.Type.API_INPUT_VALIDATION, type(eval(Kind.valueOf(kind), 200, "{\"id\":1}")));
        assertEquals(Verdict.PASS, eval(Kind.valueOf(kind), 400, "{}").verdict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POSITIVE", "INVALID_TYPE", "NO_CREDENTIAL", "ABOVE_MAXIMUM"})
    void serverErrorIsAlwaysADefect(String kind) {
        var e = eval(Kind.valueOf(kind), 500, "{}");
        assertEquals(Finding.Type.API_SERVER_ERROR, type(e));
        assertEquals(Severity.HIGH, e.findings().getFirst().severity());
    }

    @ParameterizedTest
    @CsvSource({"NO_CREDENTIAL,401", "NO_CREDENTIAL,403", "INVALID_CREDENTIAL,401", "LOW_PRIVILEGE,403"})
    void correctRefusalPasses(String kind, int status) {
        assertEquals(Verdict.PASS, eval(Kind.valueOf(kind), status, "{}").verdict());
    }

    @Test void authBypassIsCritical() {
        var e = eval(Kind.NO_CREDENTIAL, 200, "{\"id\":1}");
        assertEquals(Finding.Type.API_AUTH_BYPASS, type(e));
        assertEquals(Severity.CRITICAL, e.findings().getFirst().severity());
        assertEquals(Finding.Type.API_AUTH_BYPASS, type(eval(Kind.INVALID_CREDENTIAL, 200, "{}")));
    }

    @Test void privilegeEscalationIsHigh() {
        var e = eval(Kind.LOW_PRIVILEGE, 200, "{}");
        assertEquals(Finding.Type.API_AUTHORIZATION, type(e));
        assertEquals(Severity.HIGH, e.findings().getFirst().severity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"at com.acme.Run.go(Run.java:12)", "java.lang.IllegalStateException: x", "Traceback (most recent call last):", "SQLSTATE[42000]", "ORA-00933", "ERROR: syntax error at or near \\\"x\\\""})
    void internalDetailsAreALeak(String body) {
        assertEquals(Finding.Type.API_INFO_LEAK, type(eval(Kind.INVALID_TYPE, 400, body)));
    }

    @Test void ordinaryErrorsAreNotALeak() {
        assertTrue(eval(Kind.INVALID_TYPE, 400, "{\"error\":\"invalid limit\"}").findings().isEmpty());
    }

    @Test void blockedIsNeverADefect() {
        var e = engine.evaluate(scenario(Kind.POSITIVE), Exchange.blocked("POST", "http://t/a", "policy"));
        assertEquals(Verdict.BLOCKED, e.verdict());
        assertTrue(e.findings().isEmpty());
    }

    @Test void noResponseAndRateLimitAreInconclusive() {
        assertEquals(Verdict.INCONCLUSIVE, engine.evaluate(scenario(Kind.POSITIVE), new Exchange("GET", "u", 0, Map.of(), "", 0, Exchange.Outcome.FAILED, "x")).verdict());
        assertEquals(Verdict.INCONCLUSIVE, eval(Kind.POSITIVE, 429, "{}").verdict());
    }

    @Test void needingCredentialsIsNotADefect() {
        assertEquals(Verdict.INCONCLUSIVE, eval(Kind.POSITIVE, 401, "{}").verdict());
    }

    @Test void speedUsesAThreshold() {
        assertFalse(engine.isSlow(1000));
        assertTrue(engine.isSlow(1001));
    }

    @Test void hostilePatternCannotHangTheRun() {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"/a\":{\"get\":{\"responses\":{\"200\":{\"description\":\"\",\"content\":{\"application/json\":{\"schema\":{\"type\":\"string\",\"pattern\":\"^(a+)+$\"}}}}}}}}}";
        var op = OpenApiReader.read(spec).getFirst();
        long start = System.currentTimeMillis();
        var e = engine.evaluate(new ApiScenario(Kind.POSITIVE, op, Map.of(), Map.of(), Credential.NONE), response(200, "application/json", "\"" + "a".repeat(60) + "!\""));
        assertTrue(System.currentTimeMillis() - start < 5000);
        assertTrue(e.findings().stream().noneMatch(f -> f.type() == Finding.Type.API_SCHEMA && f.detail().contains("timed out")));
    }

    @Test void sameExchangeSameResult() {
        assertEquals(eval(Kind.POSITIVE, 200, "{\"id\":\"x\"}"), eval(Kind.POSITIVE, 200, "{\"id\":\"x\"}"));
    }

    @Test void listStaysImmutable() {
        assertEquals(List.of(), eval(Kind.POSITIVE, 200, "{\"id\":1}").findings());
    }
}
