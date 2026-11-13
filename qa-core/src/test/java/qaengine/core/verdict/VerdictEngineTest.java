package qaengine.core.verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import qaengine.core.model.Navigation;
import qaengine.core.model.Signal;
import qaengine.core.policy.TargetScope;

class VerdictEngineTest {

    static final String O = "https://app.test";
    final VerdictEngine engine = new VerdictEngine(TargetScope.of(O));

    StepEvaluation page(int status, Signal... signals) {
        return engine.evaluate(Navigation.loaded(status, O + "/p"), ExpectedOutcome.PAGE_LOADS, true, List.of(signals));
    }

    @Test void serverErrorPageFails() {
        var e = page(500, Signal.response(O + "/p", 500, "document"));
        assertEquals(Verdict.FAIL, e.verdict());
        assertEquals(Severity.HIGH, e.findings().getFirst().severity());
    }

    @Test void notFoundPageFails() {
        var e = page(404, Signal.response(O + "/p", 404, "document"));
        assertEquals(Verdict.FAIL, e.verdict());
        assertEquals(Severity.MEDIUM, e.findings().getFirst().severity());
    }

    @ParameterizedTest
    @CsvSource({"200", "201", "204"})
    void successStatusesPass(int status) {
        assertEquals(Verdict.PASS, page(status, Signal.response(O + "/p", status, "document")).verdict());
    }

    @ParameterizedTest
    @CsvSource({"401", "403", "405", "429"})
    void theseStatusesAreNeverDefects(int status) {
        var e = page(status, Signal.response(O + "/p", status, "document"));
        assertEquals(Verdict.INCONCLUSIVE, e.verdict());
        assertTrue(e.findings().isEmpty());
    }

    @Test void noOpIsNeverAPass() {
        var e = engine.evaluate(Navigation.loaded(200, O + "/p"), ExpectedOutcome.STATE_CHANGES, false, List.of());
        assertEquals(Verdict.INCONCLUSIVE, e.verdict());
    }

    @Test void blockedActionIsNotADefect() {
        var e = engine.evaluate(Navigation.blocked(O + "/p", "policy"), ExpectedOutcome.PAGE_LOADS, false, List.of());
        assertEquals(Verdict.BLOCKED, e.verdict());
        assertTrue(e.findings().isEmpty());
    }

    @Test void policyBlockMakesScriptErrorsInconclusive() {
        var e = page(200, Signal.of(Signal.Type.BLOCKED_BY_POLICY, O + "/x", "blocked"), Signal.of(Signal.Type.PAGE_EXCEPTION, O + "/p", "TypeError"));
        assertEquals(Verdict.INCONCLUSIVE, e.verdict());
        assertTrue(e.findings().isEmpty());
    }

    @Test void thirdPartyFailuresAreNotDefects() {
        assertEquals(Verdict.PASS, page(200, Signal.response("https://cdn.other/x.js", 500, "script")).verdict());
    }

    @Test void firstPartyRequestFailureIsHigh() {
        var e = page(200, Signal.response(O + "/api", 500, "fetch"));
        assertEquals(Severity.HIGH, e.findings().getFirst().severity());
    }

    @Test void brokenImageIsLow() {
        assertEquals(Severity.LOW, page(200, Signal.response(O + "/a.png", 404, "image")).findings().getFirst().severity());
    }

    @Test void faviconIsIgnored() {
        assertEquals(Verdict.PASS, page(200, Signal.response(O + "/favicon.ico", 404, "other")).verdict());
    }

    @Test void noiseRuleSuppresses() {
        var quiet = new VerdictEngine(TargetScope.of(O), s -> s.url().contains("/known"));
        assertEquals(Verdict.PASS, quiet.evaluate(Navigation.loaded(200, O + "/p"), ExpectedOutcome.PAGE_LOADS, true, List.of(Signal.response(O + "/known", 500, "fetch"))).verdict());
    }

    @Test void sameProblemGroupsAcrossIdsAndQueries() {
        var a = Finding.of(Finding.Type.SERVER_ERROR, Severity.HIGH, O + "/orders/12?x=1", "t", "HTTP 500");
        var b = Finding.of(Finding.Type.SERVER_ERROR, Severity.HIGH, "http://other:8080/orders/99", "t", "HTTP 500");
        assertEquals(a.fingerprint(), b.fingerprint());
    }

    @Test void deterministic() {
        var s = Signal.of(Signal.Type.PAGE_EXCEPTION, O + "/p", "boom");
        assertEquals(page(200, s), page(200, s));
    }

    @Test void redirectIsInconclusive() {
        var e = engine.evaluate(Navigation.redirected(O + "/a", O + "/b"), ExpectedOutcome.PAGE_LOADS, false, List.of());
        assertEquals(Verdict.INCONCLUSIVE, e.verdict());
    }

    @Test void failedNavigationIsAFinding() {
        var e = engine.evaluate(Navigation.failed(O + "/a", "net::ERR_FAILED"), ExpectedOutcome.PAGE_LOADS, false, List.of());
        assertEquals(Finding.Type.NAVIGATION_FAILURE, e.findings().getFirst().type());
    }

    @Test void consoleErrorIsLowAndStateChangePasses() {
        var s = Signal.of(Signal.Type.CONSOLE_ERROR, O + "/p", "boom\nsecond line");
        var e = page(200, s);
        assertEquals(Severity.LOW, e.findings().getFirst().severity());
        assertEquals("boom", e.findings().getFirst().detail());
        assertEquals(Verdict.PASS, engine.evaluate(Navigation.loaded(200, O + "/p"), ExpectedOutcome.STATE_CHANGES, true, List.of()).verdict());
    }

    @Test void unknownStatusIsInconclusiveNotAPass() {
        assertEquals(Verdict.INCONCLUSIVE, page(304).verdict());
        assertEquals(Verdict.INCONCLUSIVE, page(0).verdict());
    }

    @Test void clientErrorOnARequestIsMedium() {
        assertEquals(Severity.MEDIUM, page(200, Signal.response(O + "/api", 404, "fetch")).findings().getFirst().severity());
    }

    @Test void otherSignalsAreIgnored() {
        assertEquals(Verdict.PASS, page(200, Signal.of(Signal.Type.DIALOG_DISMISSED, O + "/p", "confirm"), Signal.of(Signal.Type.REQUEST_FAILED, O + "/x", "y"), Signal.response(O + "/ok", 200, "script")).verdict());
    }

    @Test void nullsAreHandled() {
        assertEquals(Verdict.FAIL, page(200, new Signal(Signal.Type.PAGE_EXCEPTION, null, 0, null, "")).verdict());
    }
}
