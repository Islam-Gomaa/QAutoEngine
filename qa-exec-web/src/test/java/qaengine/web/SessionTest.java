package qaengine.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.auth.LocatorSpec.Strategy;
import qaengine.core.auth.LoginForm;
import qaengine.core.engine.ExplorationEngine;
import qaengine.core.engine.LoginFlow;
import qaengine.core.engine.LoginFlow.Credentials;
import qaengine.core.engine.LoginFlow.Method;
import qaengine.core.engine.LoginFlow.Spec;
import qaengine.core.engine.RunResult;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.LocalEvidenceSink;
import qaengine.core.evidence.Redactor;
import qaengine.core.model.Navigation;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionMode;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.RunBudget;
import qaengine.core.policy.ScopedException;
import qaengine.core.policy.TargetScope;
import qaengine.core.verdict.VerdictEngine;
import qaengine.fixtures.FixtureServer;

/**
 * Milestone 5: a signed-in run whose session ends signs in again once, in the same browser, and stops
 * cleanly if it ends again. Sessions never cross from one browser, or one run, to another.
 */
class SessionTest {

    static FixtureServer fixture;

    static final LoginForm FORM = new LoginForm(LocatorSpec.of(Strategy.LABEL, "Email"), LocatorSpec.of(Strategy.LABEL, "Password"), LocatorSpec.of(Strategy.TEST_ID, "sign-in"),
            LocatorSpec.of(Strategy.TEST_ID, "account-menu"), LocatorSpec.of(Strategy.TEST_ID, "sign-in-error"), 10);

    @BeforeAll static void start() throws Exception { fixture = FixtureServer.start(); }
    @AfterAll static void stop() { fixture.close(); }
    @BeforeEach void clean() { fixture.clear(); }
    @AfterEach void restore() { fixture.appSessionPageLimit(Integer.MAX_VALUE); }

    static ActionGate gate(int loginAttempts) {
        var login = new ScopedException("login", ScopedException.Purpose.AUTH, fixture.baseUrl(), "POST", "/app/session", null, Instant.now().plusSeconds(600), 5, "test",
                ScopedException.ApproverRole.ORGANIZATION_ADMIN);
        var budget = new RunBudget(500, 200, 10, Duration.ofMinutes(10), loginAttempts);
        var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, TargetScope.of(fixture.baseUrl()), List.of(login), Set.of(), budget);
        return new ActionGate(policy, Clock.systemUTC(), line -> { });
    }

    static Spec spec() {
        return new Spec(Method.PASSWORD, fixture.baseUrl(), "/app/login", "/app/home", "login", FORM);
    }

    static Credentials creds() {
        return new Credentials(FixtureServer.TEST_USER, FixtureServer.TEST_PASSWORD, null, null, null);
    }

    /** Signs in, then explores the signed-in area with the same keeper the worker uses. */
    static RunResult explore(PlaywrightBrowser browser, ActionGate gate, EvidenceCollector evidence) {
        var flow = new LoginFlow(gate, browser);
        assertEquals(LoginFlow.Outcome.SIGNED_IN, flow.attempt(spec(), creds(), null).outcome());
        var collector = evidence != null ? evidence : new EvidenceCollector((type, step, content) -> null, new Redactor(List.of()), 1 << 20);
        return new ExplorationEngine(gate, browser, new VerdictEngine(gate.policy().scope()), collector, 5, Clock.systemUTC(), List.of())
                .sessionEndsAt("/app/login")
                .keepSessionWith(() -> {
                    if (flow.stillSignedIn(spec())) return ExplorationEngine.Restore.STILL_SIGNED_IN;
                    return flow.attempt(spec(), creds(), null).outcome() == LoginFlow.Outcome.SIGNED_IN
                            ? ExplorationEngine.Restore.SIGNED_IN_AGAIN : ExplorationEngine.Restore.FAILED;
                })
                .explore(fixture.url("/app/home"));
    }

    static long notes(RunResult r, String note) {
        return r.steps().stream().filter(s -> s.note().equals(note)).count();
    }

    @Test void aSessionThatTimesOutIsRestoredOnceAndEveryPageIsStillExplored() {
        fixture.appSessionPageLimit(5);
        int before = fixture.appSignIns();
        var gate = gate(2);
        RunResult r;
        try (var b = new PlaywrightBrowser(gate)) {
            r = explore(b, gate, null);
        }
        assertEquals(2, fixture.appSignIns() - before, "the run's sign-in, and one more");
        assertEquals(1, notes(r, "session ended; signed in again"));
        for (String page : List.of("/app/orders", "/app/settings", "/app/reports", "/app/team")) assertTrue(fixture.reached(page), page);
        assertEquals(RunResult.StopReason.EXHAUSTED, r.stop());
    }

    @Test void aSessionThatEndsAgainAfterTheFreshSignInStopsTheRunCleanly() {
        fixture.appSessionPageLimit(2);
        int before = fixture.appSignIns();
        var gate = gate(2);
        RunResult r;
        try (var b = new PlaywrightBrowser(gate)) {
            r = explore(b, gate, null);
        }
        assertEquals(2, fixture.appSignIns() - before, "never a third sign-in");
        assertEquals(RunResult.StopReason.SESSION_EXPIRED, r.stop());
    }

    @Test void whenTheAttemptLimitIsOneTheRunStopsInsteadOfSigningInAgain() {
        fixture.appSessionPageLimit(3);
        int before = fixture.appSignIns();
        var gate = gate(1); // production's limit
        RunResult r;
        try (var b = new PlaywrightBrowser(gate)) {
            r = explore(b, gate, null);
        }
        assertEquals(1, fixture.appSignIns() - before);
        assertEquals(RunResult.StopReason.SESSION_EXPIRED, r.stop());
    }

    @Test void sessionsNeverCrossFromOneBrowserToAnother() {
        var gateA = gate(2);
        var gateB = gate(2);
        try (var a = new PlaywrightBrowser(gateA); var b = new PlaywrightBrowser(gateB)) {
            assertEquals(LoginFlow.Outcome.SIGNED_IN, new LoginFlow(gateA, a).attempt(spec(), creds(), null).outcome());
            Navigation other = b.open(fixture.url("/app/home"));
            assertEquals(Navigation.Outcome.REDIRECTED, other.outcome(), "the second browser is not signed in");
            assertEquals(fixture.url("/app/login"), other.redirectTarget());
            assertEquals(Navigation.Outcome.LOADED, a.open(fixture.url("/app/home")).outcome(), "the first still is");
        }
        try (var later = new PlaywrightBrowser(gate(2))) {
            assertEquals(Navigation.Outcome.REDIRECTED, later.open(fixture.url("/app/home")).outcome(), "nothing outlives a closed browser");
        }
    }

    @Test void twoSignInsGetTwoSessionsAndNeitherIsReused() {
        Set<String> before = new HashSet<>(fixture.liveAppSessions());
        var gateA = gate(2);
        var gateB = gate(2);
        try (var a = new PlaywrightBrowser(gateA); var b = new PlaywrightBrowser(gateB)) {
            new LoginFlow(gateA, a).attempt(spec(), creds(), null);
            new LoginFlow(gateB, b).attempt(spec(), creds(), null);
        }
        Set<String> created = new HashSet<>(fixture.liveAppSessions());
        created.removeAll(before);
        assertEquals(2, created.size());
    }

    @Test void theSessionTokenAppearsInNoResultOrEvidence(@TempDir Path dir) throws Exception {
        Set<String> before = new HashSet<>(fixture.liveAppSessions());
        var gate = gate(2);
        var evidence = new EvidenceCollector(new LocalEvidenceSink(dir), new Redactor(List.of(FixtureServer.TEST_PASSWORD)), 1L << 30);
        RunResult r;
        try (var b = new PlaywrightBrowser(gate)) {
            r = explore(b, gate, evidence);
        }
        Set<String> tokens = new HashSet<>(fixture.liveAppSessions());
        tokens.removeAll(before);
        assertFalse(tokens.isEmpty());
        for (String token : tokens) {
            assertFalse(r.toString().contains(token), "result");
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) assertFalse(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1).contains(token), f.toString());
            }
        }
    }
}
