package qaengine.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qaengine.core.policy.ActionRequest.Kind;
import qaengine.core.policy.HttpRequestInfo.Resource;
import qaengine.core.policy.ScopedException.ApproverRole;
import qaengine.core.policy.ScopedException.Purpose;

class ActionGateTest {

    static final String O = "https://app.test";
    static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    final List<String> audit = new ArrayList<>();
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    ActionGate gate(ScopedException... ex) {
        var p = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.STAGING, TargetScope.of(O), List.of(ex), Set.of("nuke"), RunBudget.defaults());
        return new ActionGate(p, clock, audit::add);
    }

    static ScopedException exception(String id, Purpose p, String method, String path, String gql, int uses, Instant exp) {
        return new ScopedException(id, p, O, method, path, gql, exp, uses, "admin", ApproverRole.PROJECT_ADMIN);
    }

    Decision get(ActionGate g, String url) {
        return g.decideRequest(HttpRequestInfo.of("GET", url, Resource.DOCUMENT));
    }

    @Test void plainReadIsAllowed() {
        assertTrue(get(gate(), O + "/about").allowed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "TRACE"})
    void stateChangingMethodsAreBlocked(String m) {
        var d = gate().decideRequest(HttpRequestInfo.of(m, O + "/x", Resource.FETCH));
        assertFalse(d.allowed());
        assertEquals(Decision.Reason.UNSAFE_METHOD, d.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://app.test.evil.io/", "https://evil.io/app.test", "https://app.test:8443/", "http://app.test/",
            "https://user@app.test/", "https://app.test@evil.io/", "ftp://app.test/", "javascript:alert(1)", "file:///etc/passwd", "not a url"})
    void lookalikesAndOtherSchemesAreOutOfScope(String url) {
        assertFalse(get(gate(), url).allowed(), url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/items/5/delete", "/items?action=remove&id=5", "/logout", "/log-out", "/sign-out", "/signOut", "/account/deleteUser",
            "/verify/9f8e7d6c5b4a39281706f5e4d3c2b1a0", "/unsubscribe?u=1", "/p?do=nuke", "/%D8%AD%D8%B0%D9%81/5", "/download/report.bin"})
    void destructiveGetsAreDenied(String path) {
        var d = get(gate(), O + path);
        assertFalse(d.allowed(), path);
        assertEquals(Decision.Reason.HARD_DENY, d.reason());
    }

    @Test void staticAssetsWithHashedNamesAreAllowed() {
        var d = gate().decideRequest(HttpRequestInfo.of("GET", O + "/assets/9f8e7d6c5b4a39281706f5e4d3c2b1a0.js", Resource.STATIC));
        assertTrue(d.allowed());
    }

    @Test void methodOverrideIsTreatedAsTheOverridingMethod() {
        var g = gate();
        assertFalse(g.decideRequest(new HttpRequestInfo("GET", O + "/x", Map.of("X-HTTP-Method-Override", "DELETE"), null, Resource.FETCH)).allowed());
        assertFalse(g.decideRequest(HttpRequestInfo.of("GET", O + "/x?_method=delete", Resource.FETCH)).allowed());
        var e = exception("r", Purpose.READ_OPERATION, "POST", "/search", null, 5, NOW.plusSeconds(60));
        assertFalse(gate(e).decideRequest(new HttpRequestInfo("POST", O + "/search", Map.of(), "_method=delete", Resource.FETCH)).allowed());
    }

    @Test void readExceptionIsExactAndLimited() {
        var e = exception("r", Purpose.READ_OPERATION, "POST", "/search", null, 2, NOW.plusSeconds(60));
        var g = gate(e);
        var ok = HttpRequestInfo.of("POST", O + "/search", Resource.FETCH);
        assertTrue(g.decideRequest(ok).allowed());
        assertFalse(g.decideRequest(HttpRequestInfo.of("POST", O + "/search/all", Resource.FETCH)).allowed());
        assertFalse(g.decideRequest(HttpRequestInfo.of("PUT", O + "/search", Resource.FETCH)).allowed());
        assertTrue(g.decideRequest(ok).allowed());
        assertFalse(g.decideRequest(ok).allowed(), "use limit");
    }

    @Test void expiredExceptionIsRefused() {
        var e = exception("r", Purpose.READ_OPERATION, "POST", "/search", null, 2, NOW.minusSeconds(1));
        assertFalse(gate(e).decideRequest(HttpRequestInfo.of("POST", O + "/search", Resource.FETCH)).allowed());
    }

    @Test void pathTemplateMatchesOneSegment() {
        var e = exception("r", Purpose.READ_OPERATION, "POST", "/orders/{id}/summary", null, 5, NOW.plusSeconds(60));
        var g = gate(e);
        assertTrue(g.decideRequest(HttpRequestInfo.of("POST", O + "/orders/42/summary", Resource.FETCH)).allowed());
        assertFalse(g.decideRequest(HttpRequestInfo.of("POST", O + "/orders/4/2/summary", Resource.FETCH)).allowed());
    }

    @Test void exceptionsCannotOverrideHardDeny() {
        var e = exception("r", Purpose.READ_OPERATION, "POST", "/orders/delete", null, 5, NOW.plusSeconds(60));
        assertFalse(gate(e).decideRequest(HttpRequestInfo.of("POST", O + "/orders/delete", Resource.FETCH)).allowed());
    }

    @Test void graphqlOnlyNamedQueries() {
        var e = exception("q", Purpose.READ_OPERATION, "POST", "/graphql", "SearchItems", 9, NOW.plusSeconds(60));
        var g = gate(e);
        var good = new HttpRequestInfo("POST", O + "/graphql", Map.of(), "{\"query\":\"query SearchItems { items { id } }\"}", Resource.FETCH);
        assertTrue(g.decideRequest(good).allowed());
        for (String bad : List.of(
                "{\"query\":\"mutation SearchItems { del }\"}",
                "{\"query\":\"query Other { a }\"}",
                "[{\"query\":\"query SearchItems { a }\"}]",
                "{\"query\":\"query SearchItems { a } mutation M { b }\"}",
                "{\"query\":\"{ items { id } }\"}",
                "{\"extensions\":{\"persistedQuery\":{\"sha256Hash\":\"x\"}}}")) {
            assertFalse(g.decideRequest(new HttpRequestInfo("POST", O + "/graphql", Map.of(), bad, Resource.FETCH)).allowed(), bad);
        }
    }

    @Test void fieldNamedMutationIsNotAnOperation() {
        assertEquals("Q", GraphQlInspector.singleNamedQuery("{\"query\":\"query Q { mutation { id } }\"}").orElse(""));
    }

    @Test void authWindowAllowsOnlyLoginAndCloses() {
        var e = exception("login", Purpose.AUTH, "POST", "/session", null, 5, NOW.plusSeconds(60));
        var g = gate(e);
        var post = HttpRequestInfo.of("POST", O + "/session", Resource.DOCUMENT);
        assertFalse(g.decideRequest(post).allowed(), "closed by default");
        assertTrue(g.openAuthWindow("login").allowed());
        assertTrue(g.decideRequest(post).allowed());
        assertFalse(g.decideRequest(HttpRequestInfo.of("POST", O + "/other", Resource.FETCH)).allowed());
        assertFalse(get(g, O + "/logout").allowed(), "logout is denied during a run");
        g.closeAuthWindow();
        assertFalse(g.decideRequest(post).allowed(), "closed again");
    }

    @Test void loginAttemptsAreLimited() {
        var g = gate(exception("login", Purpose.AUTH, "POST", "/session", null, 9, NOW.plusSeconds(60)));
        assertTrue(g.openAuthWindow("login").allowed());
        g.closeAuthWindow();
        assertTrue(g.openAuthWindow("login").allowed());
        g.closeAuthWindow();
        assertEquals(Decision.Reason.BUDGET_EXCEEDED, g.openAuthWindow("login").reason());
    }

    @Test void oneSignInWindowCoversTheCodeAndResendAsOneAttempt() {
        var login = exception("login", Purpose.AUTH, "POST", "/session", null, 5, NOW.plusSeconds(60));
        var code = exception("code", Purpose.AUTH, "POST", "/otp-verify", null, 5, NOW.plusSeconds(60));
        var resend = exception("resend", Purpose.AUTH, "POST", "/otp-resend", null, 5, NOW.plusSeconds(60));
        var g = gate(login, code, resend);
        var verify = HttpRequestInfo.of("POST", O + "/otp-verify", Resource.DOCUMENT);
        assertTrue(g.openAuthWindow("login").allowed());
        assertFalse(g.decideRequest(verify).allowed(), "the code path is not part of a password-only window");
        g.closeAuthWindow();

        assertTrue(g.openAuthWindow("login", "code", "resend").allowed());
        assertTrue(g.decideRequest(HttpRequestInfo.of("POST", O + "/session", Resource.DOCUMENT)).allowed());
        assertTrue(g.decideRequest(verify).allowed());
        assertTrue(g.decideRequest(HttpRequestInfo.of("POST", O + "/otp-resend", Resource.DOCUMENT)).allowed());
        assertFalse(g.decideRequest(HttpRequestInfo.of("POST", O + "/other", Resource.DOCUMENT)).allowed());
        g.closeAuthWindow();
        assertFalse(g.decideRequest(verify).allowed(), "closed again, all of it");
        assertEquals(Decision.Reason.BUDGET_EXCEEDED, g.openAuthWindow("login", "code").reason(), "two windows were two attempts, not four");
    }

    @Test void aWindowNamingAMissingCompanionOpensNothing() {
        var g = gate(exception("login", Purpose.AUTH, "POST", "/session", null, 5, NOW.plusSeconds(60)),
                exception("read", Purpose.READ_OPERATION, "POST", "/otp-verify", null, 5, NOW.plusSeconds(60)));
        assertEquals(Decision.Reason.AUTH_WINDOW_CLOSED, g.openAuthWindow("login", "missing").reason());
        assertEquals(Decision.Reason.AUTH_WINDOW_CLOSED, g.openAuthWindow("login", "read").reason(), "only AUTH exceptions can join a sign-in window");
        assertFalse(g.authWindowOpen());
        assertTrue(g.openAuthWindow("login").allowed(), "a refused window spent no attempt");
    }

    @Test void onlyAllowlistedInteractions() {
        var g = gate();
        assertTrue(g.decideAction(new ActionRequest(Kind.NAVIGATE_LINK, O + "/about", Map.of("text", "About"))).allowed());
        assertTrue(g.decideAction(new ActionRequest(Kind.DISCLOSURE, null, Map.of("text", "Details"))).allowed());
        for (Kind k : List.of(Kind.BUTTON, Kind.FORM_FILL, Kind.FORM_SUBMIT, Kind.DOWNLOAD, Kind.UPLOAD, Kind.DIALOG_ACCEPT, Kind.UNKNOWN)) {
            assertFalse(g.decideAction(new ActionRequest(k, null, Map.of("text", "Harmless"))).allowed(), k.name());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"data-method", "data-confirm", "download", "onclick"})
    void riskyLinkAttributesAreBlocked(String attr) {
        var value = attr.equals("onclick") ? "return confirm('x')" : "delete";
        assertFalse(gate().decideAction(new ActionRequest(Kind.NAVIGATE_LINK, O + "/items/9", Map.of(attr, value))).allowed());
    }

    @Test void iconButtonLabelIsConsidered() {
        assertEquals(Decision.Reason.HARD_DENY, gate().decideAction(new ActionRequest(Kind.DISCLOSURE, null, Map.of("aria-label", "Delete account"))).reason());
    }

    @Test void validationModeFillsButNeverSubmits() {
        var p = new ExecutionPolicy(ExecutionMode.VALIDATION_ONLY, EnvironmentClass.DEV, TargetScope.of(O), List.of(), Set.of(), RunBudget.defaults());
        var g = new ActionGate(p, clock, audit::add);
        assertTrue(g.decideAction(new ActionRequest(Kind.FORM_FILL, null, Map.of())).allowed());
        assertFalse(g.decideAction(new ActionRequest(Kind.FORM_SUBMIT, null, Map.of())).allowed());
        assertFalse(g.decideRequest(HttpRequestInfo.of("POST", O + "/validate", Resource.FETCH)).allowed());
    }

    @Test void productionRules() {
        var scoped = new TargetScope(Set.of(O), List.of("/reports"), List.of());
        assertThrows(PolicyException.class, () -> ExecutionPolicy.readOnly(EnvironmentClass.PRODUCTION, TargetScope.of(O)));
        assertThrows(PolicyException.class, () -> new ExecutionPolicy(ExecutionMode.VALIDATION_ONLY, EnvironmentClass.PRODUCTION, scoped, List.of(), Set.of(), RunBudget.production()));
        var byProjectAdmin = exception("x", Purpose.READ_OPERATION, "POST", "/a", null, 1, NOW.plusSeconds(1));
        assertThrows(PolicyException.class, () -> new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.PRODUCTION, scoped, List.of(byProjectAdmin), Set.of(), RunBudget.production()));
        var g = new ActionGate(ExecutionPolicy.readOnly(EnvironmentClass.PRODUCTION, scoped), clock, audit::add);
        assertTrue(get(g, O + "/reports/q1").allowed());
        assertFalse(get(g, O + "/other").allowed());
    }

    @Test void mutationModeIsUnavailable() {
        assertThrows(PolicyException.class, () -> new ExecutionPolicy(ExecutionMode.AUTHORIZED_MUTATION, EnvironmentClass.DEV, TargetScope.of(O), List.of(), Set.of(), RunBudget.defaults()));
    }

    @Test void wildcardsAndMissingLimitsAreRefused() {
        assertThrows(PolicyException.class, () -> TargetScope.of("https://*.app.test"));
        assertThrows(PolicyException.class, () -> exception("x", Purpose.READ_OPERATION, "POST", "/a/*", null, 1, NOW));
        assertThrows(PolicyException.class, () -> exception("x", Purpose.READ_OPERATION, "POST", "/a", null, 0, NOW));
        assertThrows(PolicyException.class, () -> new ScopedException("x", Purpose.READ_OPERATION, O, "POST", "/a", null, null, 1, "a", ApproverRole.PROJECT_ADMIN));
    }

    @Test void budgetStopsTheRun() {
        var p = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.DEV, TargetScope.of(O), List.of(), Set.of(), new RunBudget(1, 1, 1, Duration.ofMinutes(1), 1));
        var g = new ActionGate(p, clock, audit::add);
        var a = new ActionRequest(Kind.NAVIGATE_LINK, O + "/a", Map.of());
        assertTrue(g.decideAction(a).allowed());
        assertEquals(Decision.Reason.BUDGET_EXCEEDED, g.decideAction(a).reason());
    }

    @Test void malformedInputFailsClosed() {
        assertFalse(gate().decideRequest(HttpRequestInfo.of("GET", "http://[bad", Resource.DOCUMENT)).allowed());
    }

    @Test void destructiveGetDisguisedAsStaticAssetIsDenied() {
        var d = gate().decideRequest(HttpRequestInfo.of("GET", O + "/items/5/delete", Resource.STATIC));
        assertEquals(Decision.Reason.HARD_DENY, d.reason());
    }

    @Test void graphqlOverGetIsDenied() {
        var g = gate();
        assertFalse(get(g, O + "/graphql?query=mutation%20Drop%20%7B%20drop%20%7D").allowed());
        assertFalse(get(g, O + "/api/graphql?query=%7Bitems%7D").allowed());
    }

    @Test void dotSegmentsCannotEscapeScope() {
        var p = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.STAGING,
                new TargetScope(Set.of(O), List.of("/reports"), List.of("/reports/private")), List.of(), Set.of(), RunBudget.defaults());
        var g = new ActionGate(p, clock, audit::add);
        assertTrue(get(g, O + "/reports/q1").allowed());
        assertFalse(get(g, O + "/reports/../admin").allowed());
        assertFalse(get(g, O + "/reports/x/../private/a").allowed());
    }

    @Test void authWindowDoesNotRelaxLinkLabels() {
        var g = gate(exception("login", Purpose.AUTH, "POST", "/session", null, 5, NOW.plusSeconds(60)));
        g.openAuthWindow("login");
        assertFalse(g.decideAction(new ActionRequest(Kind.NAVIGATE_LINK, O + "/x", Map.of("text", "Delete"))).allowed());
        assertTrue(g.decideAction(new ActionRequest(Kind.FORM_SUBMIT, null, Map.of("text", "Confirm"))).allowed());
    }

    @Test void encodedAndSeparatedTokensAreCaught() {
        var g = gate();
        for (String p : List.of("/items/5/%64elete", "/items;action=delete", "/a?x=%6C%6F%67%6F%75%74", "/DELETE/5", "/a/Remove-Item")) {
            assertFalse(get(g, O + p).allowed(), p);
        }
    }

    @Test void auditNeverRecordsQueryValues() {
        get(gate(), O + "/a?token=supersecret");
        assertTrue(audit.stream().noneMatch(l -> l.contains("supersecret")));
    }
}
