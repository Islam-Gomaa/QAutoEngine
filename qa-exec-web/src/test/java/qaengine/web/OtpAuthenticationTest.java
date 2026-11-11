package qaengine.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qaengine.core.auth.AuthenticationResult;
import qaengine.core.auth.AuthenticationResult.Failure;
import qaengine.core.auth.AuthenticationResult.Status;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.auth.LocatorSpec.Strategy;
import qaengine.core.auth.LoginForm;
import qaengine.core.auth.OtpSource;
import qaengine.core.auth.OtpStep;
import qaengine.core.engine.LoginFlow;
import qaengine.core.engine.LoginFlow.Credentials;
import qaengine.core.engine.LoginFlow.Method;
import qaengine.core.engine.LoginFlow.Spec;
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
import qaengine.fixtures.FixtureServer;

/**
 * Milestone 4: password then one-time code, in one browser session, judged by the fixture. The code
 * source reads the fixture's outbox the way the platform reads a mailbox: only codes sent after the
 * latest request count, and it never hands out a code it has handed out before.
 */
class OtpAuthenticationTest {

    static FixtureServer fixture;
    final List<String> audit = new ArrayList<>();

    static final LocatorSpec EMAIL = LocatorSpec.of(Strategy.LABEL, "Email");
    static final LocatorSpec PASSWORD = LocatorSpec.of(Strategy.LABEL, "Password");
    static final LocatorSpec SIGN_IN = LocatorSpec.of(Strategy.TEST_ID, "sign-in");
    static final LocatorSpec ACCOUNT = LocatorSpec.of(Strategy.TEST_ID, "account-menu");
    static final LocatorSpec CODE = LocatorSpec.of(Strategy.LABEL, "One-time code");
    static final LocatorSpec VERIFY = new LocatorSpec(Strategy.ROLE, "button", "Verify");
    static final LocatorSpec CODE_ERROR = LocatorSpec.of(Strategy.TEST_ID, "otp-error");
    static final LocatorSpec RESEND = LocatorSpec.of(Strategy.TEST_ID, "resend");

    @BeforeAll static void start() throws Exception { fixture = FixtureServer.start(); }
    @AfterAll static void stop() { fixture.close(); }
    @AfterEach void restore() { fixture.otpLifetime(120_000); }
    @org.junit.jupiter.api.BeforeEach void clean() { fixture.clear(); }

    /** Delivers the newest code sent since the last request, once, when the predicate allows it for that request number. */
    static final class FixtureCodes implements OtpSource {
        final IntPredicate deliverOnRequest;
        final List<String> handedOut = new ArrayList<>();
        Redactor redactor;
        String replacement;
        OtpSource.Reply failure;
        int sentBefore;
        int requests;
        boolean waited;

        FixtureCodes(IntPredicate deliverOnRequest) {
            this.deliverOnRequest = deliverOnRequest;
        }

        @Override public void requested() { sentBefore = fixture.sentCodes().size(); requests++; }
        @Override public void waiting() { waited = true; }

        @Override public Reply poll() {
            if (failure != null) return failure;
            List<String> sent = fixture.sentCodes();
            if (sent.size() <= sentBefore || !deliverOnRequest.test(requests)) return Reply.waitFor(Duration.ofMillis(200));
            String code = replacement != null ? replacement : sent.getLast();
            if (handedOut.contains(code)) return Reply.waitFor(Duration.ofMillis(200));
            handedOut.add(code);
            if (redactor != null) redactor.remember(code);
            return Reply.code(code);
        }
    }

    ActionGate gate(boolean withCodePermission) {
        List<ScopedException> exceptions = new ArrayList<>();
        exceptions.add(exception("login", "/app/session-otp"));
        if (withCodePermission) {
            exceptions.add(exception("otp", "/app/otp-verify"));
            exceptions.add(exception("otp-resend", "/app/otp-resend"));
        }
        var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, TargetScope.of(fixture.baseUrl()), exceptions, Set.of(), RunBudget.defaults());
        return new ActionGate(policy, Clock.systemUTC(), audit::add);
    }

    static ScopedException exception(String id, String path) {
        return new ScopedException(id, ScopedException.Purpose.AUTH, fixture.baseUrl(), "POST", path, null, Instant.now().plusSeconds(600), 5, "test",
                ScopedException.ApproverRole.PROJECT_ADMIN);
    }

    static Spec spec(String loginPath, LocatorSpec field, LocatorSpec failure, int maxResends, Duration wait) {
        var otp = new OtpStep(field, VERIFY, failure, maxResends > 0 ? RESEND : null, maxResends, wait, "otp", maxResends > 0 ? "otp-resend" : null);
        return new Spec(Method.PASSWORD_EMAIL_OTP, fixture.baseUrl(), loginPath, "/app/home", "login", new LoginForm(EMAIL, PASSWORD, SIGN_IN, ACCOUNT, null, 10), otp);
    }

    static Credentials creds(String password) {
        return new Credentials(FixtureServer.TEST_USER, password, null, null, null);
    }

    AuthenticationResult attempt(Spec spec, String password, OtpSource codes, boolean withCodePermission) {
        var gate = gate(withCodePermission);
        try (var b = new PlaywrightBrowser(gate)) {
            return new LoginFlow(gate, b).attempt(spec, creds(password), null, codes).result();
        }
    }

    // ---- success

    @Test void passwordThenEmailedCodeSignsInInOneSession() {
        var codes = new FixtureCodes(n -> true);
        var r = attempt(spec("/app/login-otp", CODE, CODE_ERROR, 0, Duration.ofSeconds(20)), FixtureServer.TEST_PASSWORD, codes, true);
        assertEquals(Status.AUTHENTICATED, r.status(), r.detail());
        assertTrue(codes.waited, "the flow said it was waiting, so a person could be asked");
        assertEquals(1, codes.requests);
        assertTrue(fixture.reached("/app/home"));
    }

    @Test void aCodeSplitAcrossSixBoxesIsTypedOneCharacterPerBox() {
        var r = attempt(spec("/app/login-otp-boxes", LocatorSpec.of(Strategy.CSS, "input.digit"), CODE_ERROR, 0, Duration.ofSeconds(20)), FixtureServer.TEST_PASSWORD, new FixtureCodes(n -> true), true);
        assertEquals(Status.AUTHENTICATED, r.status(), r.detail());
    }

    @Test void aLostEmailIsReplacedThroughTheConfiguredResendButtonAndOnlyThen() {
        int before = fixture.sentCodes().size();
        var codes = new FixtureCodes(n -> n >= 2); // the first email never arrives
        var r = attempt(spec("/app/login-otp", CODE, CODE_ERROR, 1, Duration.ofSeconds(20)), FixtureServer.TEST_PASSWORD, codes, true);
        assertEquals(Status.AUTHENTICATED, r.status(), r.detail());
        assertEquals(2, codes.requests);
        assertEquals(before + 2, fixture.sentCodes().size(), "exactly one resend");
    }

    // ---- codes refused or missing

    @Test void aWrongCodeIsCertainWhenThePagesOwnErrorIsConfigured() {
        var codes = new FixtureCodes(n -> true);
        codes.replacement = "000000";
        var r = attempt(spec("/app/login-otp", CODE, CODE_ERROR, 0, Duration.ofSeconds(20)), FixtureServer.TEST_PASSWORD, codes, true);
        assertEquals(Failure.OTP_INVALID, r.failure(), r.detail());
        assertTrue(r.detail().contains("code error"), r.detail());
        assertEquals(AuthenticationResult.Stage.OTP, r.stage());
    }

    @Test void aWrongCodeIsInferredWhenTheCodeFormComesBack() {
        var codes = new FixtureCodes(n -> true);
        codes.replacement = "000000";
        var r = attempt(spec("/app/login-otp", CODE, null, 0, Duration.ofSeconds(20)), FixtureServer.TEST_PASSWORD, codes, true);
        assertEquals(Failure.OTP_INVALID, r.failure(), r.detail());
        assertTrue(r.detail().contains("probably"), r.detail());
    }

    @Test void anExpiredCodeIsRefused() {
        fixture.otpLifetime(1);
        var r = attempt(spec("/app/login-otp", CODE, CODE_ERROR, 0, Duration.ofSeconds(20)), FixtureServer.TEST_PASSWORD, new FixtureCodes(n -> true), true);
        assertEquals(Failure.OTP_INVALID, r.failure(), r.detail());
    }

    @Test void noCodeWithinTheWaitIsReportedAndNothingIsResentWithoutPermission() {
        int before = fixture.sentCodes().size();
        var r = attempt(spec("/app/login-otp", CODE, CODE_ERROR, 0, Duration.ofSeconds(10)), FixtureServer.TEST_PASSWORD, new FixtureCodes(n -> false), true);
        assertEquals(Failure.OTP_NOT_RECEIVED, r.failure(), r.detail());
        assertEquals(before + 1, fixture.sentCodes().size(), "no resend was configured, so none was asked for");
    }

    @Test void aSourceThatCannotReadTheCodeStopsTheAttemptWithItsReason() {
        var codes = new FixtureCodes(n -> true);
        codes.failure = OtpSource.Reply.stop(Failure.OTP_EXTRACTION_FAILED, "A matching email arrived but the code pattern found nothing in it");
        var r = attempt(spec("/app/login-otp", CODE, CODE_ERROR, 0, Duration.ofSeconds(20)), FixtureServer.TEST_PASSWORD, codes, true);
        assertEquals(Failure.OTP_EXTRACTION_FAILED, r.failure());
        assertTrue(r.detail().contains("pattern"));
    }

    @Test void aWrongPasswordNeverReachesTheCodeStep() {
        var codes = new FixtureCodes(n -> true);
        var r = attempt(spec("/app/login-otp", CODE, CODE_ERROR, 0, Duration.ofSeconds(20)), "wrong", codes, true);
        assertEquals(Failure.INVALID_CREDENTIALS, r.failure(), r.detail());
        assertFalse(codes.waited);
    }

    @Test void withoutPermissionForTheCodeSubmissionNothingIsTypedAtAll() {
        var r = attempt(spec("/app/login-otp", CODE, CODE_ERROR, 0, Duration.ofSeconds(20)), FixtureServer.TEST_PASSWORD, new FixtureCodes(n -> true), false);
        assertEquals(Failure.POLICY_REFUSED, r.failure(), r.detail());
        assertFalse(fixture.reached("/app/otp-verify"));
    }

    // ---- leaks

    @Test void theCodeAppearsInNoResultEvidenceOrAuditLine(@TempDir Path dir) throws Exception {
        var redactor = new Redactor(List.of(FixtureServer.TEST_PASSWORD));
        var collector = new EvidenceCollector(new LocalEvidenceSink(dir), redactor, 1L << 30);
        var codes = new FixtureCodes(n -> true);
        codes.redactor = redactor;
        var gate = gate(true);
        AuthenticationResult r;
        try (var b = new PlaywrightBrowser(gate)) {
            r = new LoginFlow(gate, b).attempt(spec("/app/login-otp", CODE, CODE_ERROR, 0, Duration.ofSeconds(20)), creds(FixtureServer.TEST_PASSWORD),
                    new LoginFlow.Evidence(collector, -1), codes).result();
        }
        assertEquals(Status.AUTHENTICATED, r.status(), r.detail());
        String code = codes.handedOut.getFirst();
        assertFalse(r.toString().contains(code), "result");
        assertTrue(audit.stream().noneMatch(l -> l.contains(code)), "audit lines");
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) assertFalse(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1).contains(code), f.toString());
        }
    }
}
