package qaengine.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import qaengine.core.policy.ActionRequest.Kind;
import qaengine.core.policy.HttpRequestInfo.Resource;

class PolicyEdgeCasesTest {

    static final String O = "https://app.test";
    final Clock clock = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);

    ActionGate gate(TargetScope scope, RunBudget budget) {
        return new ActionGate(new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.STAGING, scope, List.of(), Set.of(), budget), clock, l -> { });
    }

    @ParameterizedTest
    @CsvSource({"https://App.Test,https://app.test:443", "http://a.test,http://a.test:80", "wss://a.test/x,https://a.test:443", "ws://a.test:9/x,http://a.test:9"})
    void originsNormalize(String in, String out) {
        assertEquals(Optional.of(out), TargetScope.originOf(in));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mailto:a@b.test", "//no-scheme", "http://", "http://u:p@a.test/", "ftp://a.test/"})
    void nonWebOriginsAreEmpty(String in) {
        assertEquals(Optional.empty(), TargetScope.originOf(in));
    }

    @Test void scopeNeedsAnOrigin() {
        assertThrows(PolicyException.class, () -> new TargetScope(Set.of(), List.of(), List.of()));
        assertThrows(PolicyException.class, () -> TargetScope.of("not a url"));
    }

    @Test void pathRules() {
        var scope = new TargetScope(Set.of(O), List.of("/a/"), List.of("/a/b"));
        assertTrue(scope.allowsPath(O + "/a/x"));
        assertFalse(scope.allowsPath(O + "/a/b/c"));
        assertFalse(scope.allowsPath(O + "/ab"));
        assertFalse(scope.allowsPath("http://[bad"));
        assertTrue(TargetScope.of(O).allowsPath(O));
    }

    @ParameterizedTest
    @CsvSource({"0,1,1,60,1", "1,0,1,60,1", "1,1,0,60,1", "1,1,1,0,1", "1,1,1,60,0", "1,1,1,-5,1"})
    void budgetsMustBePositive(int a, int p, int d, int seconds, int l) {
        assertThrows(PolicyException.class, () -> new RunBudget(a, p, d, Duration.ofSeconds(seconds), l));
    }

    @Test void deadlineBlocksEverything() {
        var moving = new Clock() {
            Instant now = Instant.parse("2026-11-06T00:00:00Z");
            @Override public Instant instant() { return now; }
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
        };
        var p = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.DEV, TargetScope.of(O), List.of(), Set.of(), new RunBudget(9, 9, 9, Duration.ofMinutes(1), 1));
        var g = new ActionGate(p, moving, l -> { });
        moving.now = moving.now.plusSeconds(61);
        assertEquals(Decision.Reason.BUDGET_EXCEEDED, g.decideRequest(HttpRequestInfo.of("GET", O + "/a", Resource.DOCUMENT)).reason());
        assertEquals(Decision.Reason.BUDGET_EXCEEDED, g.decideAction(new ActionRequest(Kind.NAVIGATE_LINK, O + "/a", Map.of())).reason());
    }

    @Test void controlSignals() {
        assertEquals(Optional.empty(), RiskRules.controlRisk(Map.of("data-method", "get", "text", "Details"), Set.of()));
        assertTrue(RiskRules.controlRisk(Map.of("title", "Purge cache"), Set.of()).isPresent());
        assertTrue(RiskRules.controlRisk(Map.of("text", "Wipe"), Set.of()).isPresent());
        assertTrue(RiskRules.controlRisk(Map.of("text", "Frobnicate"), Set.of("frobnicate")).isPresent());
        assertTrue(RiskRules.controlRisk(Map.of("data-confirm", "x"), Set.of()).isPresent());
    }

    @Test void tokensNeedLettersAndDigits() {
        assertEquals(Optional.empty(), RiskRules.urlRisk(O + "/" + "a".repeat(30), Set.of()));
        assertEquals(Optional.empty(), RiskRules.urlRisk(O + "/" + "1".repeat(30), Set.of()));
        assertTrue(RiskRules.urlRisk("http://[bad", Set.of()).isPresent());
    }

    @Test void overrideFormsAreRead() {
        assertEquals("GET", RiskRules.effectiveMethod(HttpRequestInfo.of("get", O + "/a?x=1&y", Resource.FETCH)));
        assertEquals("PUT", RiskRules.effectiveMethod(new HttpRequestInfo("POST", O, Map.of("X-Method-Override", "put"), null, Resource.FETCH)));
        assertEquals("DELETE", RiskRules.effectiveMethod(new HttpRequestInfo("POST", O, Map.of(), "a=1&_method=delete", Resource.FETCH)));
        assertEquals("POST", RiskRules.effectiveMethod(new HttpRequestInfo("POST", O, Map.of("Accept", "x"), "a=1", Resource.FETCH)));
    }

    @Test void graphqlEdgeCases() {
        assertTrue(GraphQlInspector.singleNamedQuery(null).isEmpty());
        assertTrue(GraphQlInspector.singleNamedQuery("not json").isEmpty());
        assertTrue(GraphQlInspector.singleNamedQuery("{\"query\":\"subscription S { a }\"}").isEmpty());
        assertTrue(GraphQlInspector.singleNamedQuery("{\"query\":\"query A { a }\",\"x\":{\"query\":\"mutation B { b }\"}}").isEmpty());
        assertEquals(Optional.of("A"), GraphQlInspector.singleNamedQuery("{\"query\":\"query A($q: String) { a(q: \\\"mutation\\\") }\"}"));
    }

    @Test void gateInternals() {
        var g = gate(TargetScope.of(O), RunBudget.defaults());
        assertEquals(O.length() > 0, g.policy() != null);
        assertEquals(Decision.Reason.AUTH_WINDOW_CLOSED, g.openAuthWindow("missing").reason());
        g.closeAuthWindow();
        assertFalse(g.authWindowOpen());
        assertEquals(Decision.Reason.OUT_OF_SCOPE, g.decideAction(new ActionRequest(Kind.NAVIGATE_LINK, "https://other.test/", Map.of())).reason());
        assertEquals(Decision.Reason.ACTION_NOT_ALLOWED, g.decideAction(new ActionRequest(Kind.NAVIGATE_LINK, null, Map.of())).reason());
        assertEquals(Decision.Reason.UNSAFE_METHOD, g.decideRequest(HttpRequestInfo.of("GET", O + "/ws", Resource.WEBSOCKET)).reason());
    }

    @Test void pathScopeAppliesToActionsAndRequests() {
        var g = gate(new TargetScope(Set.of(O), List.of("/in"), List.of()), RunBudget.defaults());
        assertEquals(Decision.Reason.OUT_OF_SCOPE, g.decideAction(new ActionRequest(Kind.NAVIGATE_LINK, O + "/out", Map.of())).reason());
        assertEquals(Decision.Reason.OUT_OF_SCOPE, g.decideRequest(HttpRequestInfo.of("GET", O + "/out", Resource.DOCUMENT)).reason());
        assertTrue(g.decideRequest(HttpRequestInfo.of("GET", O + "/out/a.css", Resource.STATIC)).allowed());
    }

    @Test void factoryPicksTheRightBudget() {
        var scoped = new TargetScope(Set.of(O), List.of("/a"), List.of());
        assertEquals(RunBudget.production(), ExecutionPolicy.readOnly(EnvironmentClass.PRODUCTION, scoped).budget());
        assertEquals(RunBudget.defaults(), ExecutionPolicy.readOnly(EnvironmentClass.DEV, scoped).budget());
    }

    @Test void secretsNeverTravelOverPlainHttpToRealHosts() {
        ExecutionLock.requireSecureForSecrets("https://app.test/");
        ExecutionLock.requireSecureForSecrets("http://127.0.0.1:9/");
        ExecutionLock.requireSecureForSecrets("http://fixture.qa.internal:8090/");
        assertThrows(SecurityException.class, () -> ExecutionLock.requireSecureForSecrets("http://app.test/"));
    }

    @Test void realTargetsStayLocked() {
        assertThrows(SecurityException.class, () -> ExecutionLock.requireAllowed(TargetScope.of("https://app.test")));
        ExecutionLock.requireAllowed(TargetScope.of("http://127.0.0.1:1"));
    }
}
