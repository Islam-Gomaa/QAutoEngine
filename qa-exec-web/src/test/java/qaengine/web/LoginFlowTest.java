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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qaengine.core.engine.ExplorationEngine;
import qaengine.core.engine.LoginFlow;
import qaengine.core.engine.LoginFlow.Credentials;
import qaengine.core.engine.LoginFlow.Method;
import qaengine.core.engine.LoginFlow.Outcome;
import qaengine.core.engine.LoginFlow.Spec;
import qaengine.core.engine.RunResult;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.LocalEvidenceSink;
import qaengine.core.evidence.Redactor;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionMode;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.RunBudget;
import qaengine.core.policy.ScopedException;
import qaengine.core.policy.TargetScope;
import qaengine.core.verdict.VerdictEngine;
import qaengine.fixtures.FixtureServer;

/** Phase 10 acceptance, judged by the fixture. */
class LoginFlowTest {

    static FixtureServer fixture;
    final List<String> audit = new ArrayList<>();

    @BeforeAll static void start() throws Exception { fixture = FixtureServer.start(); }
    @AfterAll static void stop() { fixture.close(); }

    ActionGate gate(String submitPath) {
        var login = new ScopedException("login", ScopedException.Purpose.AUTH, fixture.baseUrl(), "POST", submitPath, null,
                Instant.now().plusSeconds(600), 5, "test", ScopedException.ApproverRole.PROJECT_ADMIN);
        var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, TargetScope.of(fixture.baseUrl()), List.of(login), Set.of(), RunBudget.defaults());
        return new ActionGate(policy, Clock.systemUTC(), audit::add);
    }

    Spec spec(Method m, String loginPath) {
        return new Spec(m, fixture.baseUrl(), loginPath, "/account", "login");
    }

    static Credentials password(String p) {
        return new Credentials(FixtureServer.TEST_USER, p, null, null, null);
    }

    @Test void passwordLoginReachesProtectedPages() {
        var gate = gate("/session");
        try (var b = new PlaywrightBrowser(gate)) {
            assertEquals(Outcome.SIGNED_IN, new LoginFlow(gate, b).signIn(spec(Method.PASSWORD, "/login"), password(FixtureServer.TEST_PASSWORD)));
            assertFalse(gate.authWindowOpen(), "window closes after login");
        }
    }

    @Test void wrongPasswordFailsAndAttemptsAreLimited() {
        var gate = gate("/session");
        try (var b = new PlaywrightBrowser(gate)) {
            var flow = new LoginFlow(gate, b);
            assertEquals(Outcome.FAILED, flow.signIn(spec(Method.PASSWORD, "/login"), password("wrong")));
            assertEquals(Outcome.FAILED, flow.signIn(spec(Method.PASSWORD, "/login"), password("wrong")));
            fixture.clear();
            assertEquals(Outcome.BLOCKED, flow.signIn(spec(Method.PASSWORD, "/login"), password("wrong")));
            assertFalse(fixture.reached("/session"), "no third attempt reaches the server");
        }
    }

    @Test void totpLoginWorks() {
        var gate = gate("/session-totp");
        try (var b = new PlaywrightBrowser(gate)) {
            var creds = new Credentials(FixtureServer.TEST_USER, FixtureServer.TEST_PASSWORD, FixtureServer.TEST_TOTP, null, null);
            assertEquals(Outcome.SIGNED_IN, new LoginFlow(gate, b).signIn(spec(Method.TOTP, "/login-totp"), creds));
        }
    }

    @Test void pushMfaIsBlockedNotBypassed() {
        var gate = gate("/session-push");
        try (var b = new PlaywrightBrowser(gate)) {
            assertEquals(Outcome.BLOCKED, new LoginFlow(gate, b).signIn(spec(Method.PASSWORD, "/login-push"), password(FixtureServer.TEST_PASSWORD)));
        }
    }

    @Test void importedSessionWorks() {
        var gate = gate("/session");
        try (var b = new PlaywrightBrowser(gate)) {
            var creds = new Credentials(null, null, null, "session", "fixture-session");
            assertEquals(Outcome.SIGNED_IN, new LoginFlow(gate, b).signIn(spec(Method.SESSION_IMPORT, "/login"), creds));
        }
    }

    @Test void expiredImportedSessionIsNotTreatedAsSignedIn() {
        var gate = gate("/session");
        try (var b = new PlaywrightBrowser(gate)) {
            var creds = new Credentials(null, null, null, "session", "stale");
            assertEquals(Outcome.BLOCKED, new LoginFlow(gate, b).signIn(spec(Method.SESSION_IMPORT, "/login"), creds));
        }
    }

    @Test void postIsBlockedAgainAfterLoginAndLogoutIsNeverFollowed() {
        var gate = gate("/session");
        try (var b = new PlaywrightBrowser(gate)) {
            new LoginFlow(gate, b).signIn(spec(Method.PASSWORD, "/login"), password(FixtureServer.TEST_PASSWORD));
            fixture.clear();
            b.evaluate("fetch('/m/after-login',{method:'POST'}).catch(()=>{})");
            b.pause(400);
            assertFalse(fixture.reached("/m/after-login"));
            b.open(fixture.url("/logout"));
            assertFalse(fixture.reached("/logout"));
        }
    }

    @Test void sessionExpiryStopsTheRunHonestly(@TempDir Path dir) throws Exception {
        var gate = gate("/session");
        var scope = gate.policy().scope();
        var evidence = new EvidenceCollector(new LocalEvidenceSink(dir), new Redactor(List.of(FixtureServer.TEST_PASSWORD)), 1L << 30);
        try (var b = new PlaywrightBrowser(gate)) {
            assertEquals(Outcome.SIGNED_IN, new LoginFlow(gate, b).signIn(spec(Method.PASSWORD, "/login"), password(FixtureServer.TEST_PASSWORD)));
            RunResult r = new ExplorationEngine(gate, b, new VerdictEngine(scope), evidence, 1, Clock.systemUTC(), List.of())
                    .sessionEndsAt("/login").explore(fixture.url("/account"));
            assertEquals(RunResult.StopReason.SESSION_EXPIRED, r.stop());
        }
    }

    @Test void canaryPasswordAppearsInNoOutput(@TempDir Path dir) throws Exception {
        var gate = gate("/session");
        var scope = gate.policy().scope();
        var evidence = new EvidenceCollector(new LocalEvidenceSink(dir), new Redactor(List.of(FixtureServer.TEST_PASSWORD)), 1L << 30);
        RunResult r;
        try (var b = new PlaywrightBrowser(gate)) {
            new LoginFlow(gate, b).signIn(spec(Method.PASSWORD, "/login"), password(FixtureServer.TEST_PASSWORD));
            r = new ExplorationEngine(gate, b, new VerdictEngine(scope), evidence, 1, Clock.systemUTC(), List.of()).explore(fixture.url("/"));
        }
        String canary = FixtureServer.TEST_PASSWORD;
        assertFalse(r.toString().contains(canary), "run result");
        assertTrue(audit.stream().noneMatch(l -> l.contains(canary)), "audit lines");
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) assertFalse(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1).contains(canary), f.toString());
        }
    }
}
