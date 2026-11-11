package qaengine.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qaengine.core.auth.AuthenticationResult;
import qaengine.core.auth.AuthenticationResult.Failure;
import qaengine.core.auth.AuthenticationResult.Status;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.auth.LocatorSpec.Strategy;
import qaengine.core.auth.LoginForm;
import qaengine.core.engine.LoginFlow;
import qaengine.core.engine.LoginFlow.Credentials;
import qaengine.core.engine.LoginFlow.Method;
import qaengine.core.engine.LoginFlow.Spec;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.EvidenceRef;
import qaengine.core.evidence.LocalEvidenceSink;
import qaengine.core.evidence.Redactor;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionMode;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.RunBudget;
import qaengine.core.policy.ScopedException;
import qaengine.core.policy.TargetScope;
import qaengine.fixtures.FixtureServer;

/**
 * Milestone 3: configured username and password sign-in, judged by the fixture. Every failure
 * category a connection test reports is produced here by a real browser.
 */
class AuthenticationTest {

    static FixtureServer fixture;
    final List<String> audit = new ArrayList<>();

    static final LocatorSpec EMAIL = LocatorSpec.of(Strategy.LABEL, "Email");
    static final LocatorSpec PASSWORD = LocatorSpec.of(Strategy.LABEL, "Password");
    static final LocatorSpec SIGN_IN = LocatorSpec.of(Strategy.TEST_ID, "sign-in");
    static final LocatorSpec ACCOUNT = LocatorSpec.of(Strategy.TEST_ID, "account-menu");
    static final LocatorSpec ERROR = LocatorSpec.of(Strategy.TEST_ID, "sign-in-error");

    @BeforeAll static void start() throws Exception { fixture = FixtureServer.start(); }
    @AfterAll static void stop() { fixture.close(); }
    @BeforeEach void clean() { fixture.clear(); }

    ActionGate gate(String base, String submitPath, List<String> excluded) {
        var login = new ScopedException("login", ScopedException.Purpose.AUTH, base, "POST", submitPath, null,
                Instant.now().plusSeconds(600), 5, "test", ScopedException.ApproverRole.PROJECT_ADMIN);
        var scope = new TargetScope(Set.of(base), List.of(), excluded);
        var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, scope, List.of(login), Set.of(), RunBudget.defaults());
        return new ActionGate(policy, Clock.systemUTC(), audit::add);
    }

    static LoginForm form(LocatorSpec success, LocatorSpec failure, int timeout) {
        return new LoginForm(EMAIL, PASSWORD, SIGN_IN, success, failure, timeout);
    }

    static Credentials creds(String password) {
        return new Credentials(FixtureServer.TEST_USER, password, null, null, null);
    }

    AuthenticationResult attempt(String base, String submitPath, Spec spec, Credentials c) {
        var gate = gate(base, submitPath, List.of());
        try (var b = new PlaywrightBrowser(gate)) {
            return new LoginFlow(gate, b).attempt(spec, c, null).result();
        }
    }

    AuthenticationResult app(String loginPath, String successPath, LoginForm form, String password) {
        return attempt(fixture.baseUrl(), "/app/session", new Spec(Method.PASSWORD, fixture.baseUrl(), loginPath, successPath, "login", form), creds(password));
    }

    // ---- success

    @Test void configuredSelectorsSignInAndBothSuccessChecksPass() {
        var r = app("/app/login", "/app/home", form(ACCOUNT, ERROR, 10), FixtureServer.TEST_PASSWORD);
        assertEquals(Status.AUTHENTICATED, r.status(), r.detail());
        assertEquals(AuthenticationResult.Stage.DONE, r.stage());
        assertTrue(fixture.reached("/app/home"));
    }

    @Test void theLoginPageIsReadForSuggestionsWithoutTypingAnything() {
        var r = app("/app/login", "/app/home", form(ACCOUNT, null, 10), "wrong");
        assertEquals(LocatorSpec.of(Strategy.LABEL, "Email"), r.suggestions().get("username"));
        assertEquals(LocatorSpec.of(Strategy.LABEL, "Password"), r.suggestions().get("password"));
        assertEquals(LocatorSpec.of(Strategy.TEST_ID, "sign-in"), r.suggestions().get("submit"));
    }

    @Test void aLoginPageThatMovesWithinTheSiteIsFollowedThroughTheGate() {
        var r = app("/app/login-moved", "/app/home", form(null, null, 10), FixtureServer.TEST_PASSWORD);
        assertEquals(Status.AUTHENTICATED, r.status(), r.detail());
    }

    @Test void aSinglePageLoginThatNeverNavigatesStillSignsIn() {
        var spec = new Spec(Method.PASSWORD, fixture.baseUrl(), "/app/login-spa", null, "login",
                new LoginForm(EMAIL, PASSWORD, new LocatorSpec(Strategy.ROLE, "button", "Sign in"), ACCOUNT, null, 10));
        var r = attempt(fixture.baseUrl(), "/app/api/session", spec, creds(FixtureServer.TEST_PASSWORD));
        assertEquals(Status.AUTHENTICATED, r.status(), r.detail());
    }

    // ---- refused credentials

    @Test void aWrongPasswordIsCertainWhenThePagesOwnErrorIsConfigured() {
        var r = app("/app/login", "/app/home", form(ACCOUNT, ERROR, 10), "wrong");
        assertEquals(Failure.INVALID_CREDENTIALS, r.failure());
        assertTrue(r.detail().contains("sign-in error"), r.detail());
    }

    @Test void aWrongPasswordIsInferredWhenTheFormComesBack() {
        var r = app("/app/login", "/app/home", form(ACCOUNT, null, 10), "wrong");
        assertEquals(Failure.INVALID_CREDENTIALS, r.failure());
        assertTrue(r.detail().contains("probably"), "an inference says so: " + r.detail());
    }

    @Test void anErrorShownInPlaceWithoutNavigatingIsAWrongPassword() {
        var spec = new Spec(Method.PASSWORD, fixture.baseUrl(), "/app/login-spa", null, "login",
                new LoginForm(EMAIL, PASSWORD, new LocatorSpec(Strategy.ROLE, "button", "Sign in"), ACCOUNT, new LocatorSpec(Strategy.ROLE, "alert", null), 5));
        var r = attempt(fixture.baseUrl(), "/app/api/session", spec, creds("wrong"));
        assertEquals(Failure.INVALID_CREDENTIALS, r.failure(), r.detail());
    }

    // ---- verification

    @Test void anAcceptedPasswordWithoutASessionFailsVerification() {
        var spec = new Spec(Method.PASSWORD, fixture.baseUrl(), "/app/login-limbo", "/app/home", "login", form(ACCOUNT, null, 5));
        var r = attempt(fixture.baseUrl(), "/app/session-limbo", spec, creds(FixtureServer.TEST_PASSWORD));
        assertEquals(Failure.VERIFICATION_FAILED, r.failure());
        assertEquals(AuthenticationResult.Stage.VERIFY, r.stage());
    }

    @Test void theSignedInMarkerAloneCatchesALoginThatGoesNowhere() {
        var spec = new Spec(Method.PASSWORD, fixture.baseUrl(), "/app/login-limbo", null, "login", form(ACCOUNT, null, 5));
        var r = attempt(fixture.baseUrl(), "/app/session-limbo", spec, creds(FixtureServer.TEST_PASSWORD));
        assertEquals(Failure.VERIFICATION_FAILED, r.failure());
        assertTrue(r.detail().contains("account-menu"), r.detail());
    }

    // ---- the login page itself

    @Test void aLoginPageThatErrorsIsUnavailable() {
        var r = app("/app/login-broken", "/app/home", form(ACCOUNT, null, 10), FixtureServer.TEST_PASSWORD);
        assertEquals(Failure.LOGIN_PAGE_UNAVAILABLE, r.failure());
        assertTrue(r.detail().contains("500"), r.detail());
        assertEquals(Failure.LOGIN_PAGE_UNAVAILABLE, app("/app/nowhere", "/app/home", form(ACCOUNT, null, 10), FixtureServer.TEST_PASSWORD).failure());
    }

    @Test void aSelectorThatFindsNothingStopsBeforeAnythingIsTyped() {
        var wrong = new LoginForm(LocatorSpec.of(Strategy.LABEL, "Username"), PASSWORD, SIGN_IN, ACCOUNT, null, 10);
        var r = app("/app/login", "/app/home", wrong, FixtureServer.TEST_PASSWORD);
        assertEquals(Failure.LOGIN_PAGE_UNAVAILABLE, r.failure());
        assertTrue(r.detail().contains("label \"Username\""), r.detail());
        assertFalse(fixture.reached("/app/session"), "nothing was submitted");
    }

    // ---- network and policy

    @Test void aLoginPageThatSendsYouOffSiteIsRefusedAndNothingIsTyped() {
        var r = app("/app/login-offsite", "/app/home", form(ACCOUNT, null, 10), FixtureServer.TEST_PASSWORD);
        assertEquals(Failure.POLICY_REFUSED, r.failure());
        assertFalse(fixture.reached("/app/session"));
    }

    @Test void aLoginPageOutsideTheAuthorizedPathsIsRefused() {
        var gate = gate(fixture.baseUrl(), "/app/session", List.of("/app/login"));
        try (var b = new PlaywrightBrowser(gate)) {
            var r = new LoginFlow(gate, b).attempt(new Spec(Method.PASSWORD, fixture.baseUrl(), "/app/login", "/app/home", "login", form(ACCOUNT, null, 10)),
                    creds(FixtureServer.TEST_PASSWORD), null).result();
            assertEquals(Failure.POLICY_REFUSED, r.failure(), r.detail());
        }
    }

    @Test void anUnresolvableHostIsADnsFailure() {
        String base = "http://" + qaengine.core.policy.ExecutionLock.FIXTURE_HOST + ":9";
        var r = attempt(base, "/app/session", new Spec(Method.PASSWORD, base, "/app/login", "/app/home", "login", form(ACCOUNT, null, 10)), creds("x"));
        assertEquals(Failure.NETWORK_UNREACHABLE, r.failure());
        assertTrue(r.detail().contains("DNS"), r.detail());
    }

    @Test void aClosedPortIsANetworkFailure() {
        String base = "http://127.0.0.1:1";
        var r = attempt(base, "/app/session", new Spec(Method.PASSWORD, base, "/app/login", "/app/home", "login", form(ACCOUNT, null, 10)), creds("x"));
        assertEquals(Failure.NETWORK_UNREACHABLE, r.failure(), r.detail());
    }

    @Test void aServerThatDoesNotSpeakTlsIsATlsFailure() throws Exception {
        // Answers every connection in plain text, which a TLS client rejects at once.
        try (var server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try (var socket = server.accept()) {
                        socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes());
                    } catch (java.io.IOException e) {
                        return;
                    }
                }
            });
            String base = "https://localhost:" + server.getLocalPort();
            var r = attempt(base, "/app/session", new Spec(Method.PASSWORD, base, "/app/login", "/app/home", "login", form(ACCOUNT, null, 10)), creds("x"));
            assertEquals(Failure.TLS_ERROR, r.failure(), r.detail());
        }
    }

    // ---- evidence and leaks

    @Test void everyAttemptLeavesMaskedEvidenceAndNoSecretAnywhere(@TempDir Path dir) throws Exception {
        String canary = FixtureServer.TEST_PASSWORD;
        var gate = gate(fixture.baseUrl(), "/app/session", List.of());
        var collector = new EvidenceCollector(new LocalEvidenceSink(dir), new Redactor(List.of(canary)), 1L << 30);
        AuthenticationResult ok, refused;
        try (var b = new PlaywrightBrowser(gate)) {
            var flow = new LoginFlow(gate, b);
            refused = flow.attempt(new Spec(Method.PASSWORD, fixture.baseUrl(), "/app/login", "/app/home", "login", form(ACCOUNT, ERROR, 10)), creds("wrong-" + canary), new LoginFlow.Evidence(collector, -1)).result();
            ok = flow.attempt(new Spec(Method.PASSWORD, fixture.baseUrl(), "/app/login", "/app/home", "login", form(ACCOUNT, ERROR, 10)), creds(canary), new LoginFlow.Evidence(collector, -1)).result();
        }
        assertEquals(Failure.INVALID_CREDENTIALS, refused.failure());
        assertEquals(Status.AUTHENTICATED, ok.status(), ok.detail());
        for (var r : List.of(ok, refused)) {
            assertEquals(2, r.evidence().size());
            assertTrue(r.evidence().stream().allMatch(e -> e.status() == EvidenceRef.Status.STORED));
            assertFalse(r.toString().contains(canary), "the result never holds the password");
        }
        assertTrue(audit.stream().noneMatch(l -> l.contains(canary)), "audit lines");
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) assertFalse(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1).contains(canary), f.toString());
        }
    }
}
