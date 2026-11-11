package qaengine.worker;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.options.Proxy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import qaengine.api.ApiClient;
import qaengine.api.ApiRunner;
import qaengine.api.ApiVerdictEngine;
import qaengine.contracts.WorkerContracts;
import qaengine.contracts.WorkerContracts.JobAssignment;
import qaengine.core.auth.AuthenticationResult;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.auth.LoginForm;
import qaengine.core.auth.OtpStep;
import qaengine.contracts.WorkerContracts.LoginSecret;
import qaengine.contracts.WorkerContracts.RunCompletion;
import qaengine.core.engine.ExplorationEngine;
import qaengine.core.engine.LoginFlow;
import qaengine.core.engine.RunResult;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.EvidenceRef;
import qaengine.core.evidence.EvidenceSink;
import qaengine.core.evidence.EvidenceType;
import qaengine.core.evidence.LocalEvidenceSink;
import qaengine.core.evidence.Redactor;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionLock;
import qaengine.core.policy.ExecutionMode;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.RunBudget;
import qaengine.core.policy.ScopedException;
import qaengine.core.policy.TargetScope;
import qaengine.core.verdict.VerdictEngine;
import qaengine.web.PlaywrightBrowser;

/**
 * Runs exactly one job, then exits, and Docker restarts it with clean tmpfs. If the platform stops
 * answering, the worker stops acting before its lease can be given to anyone else.
 */
public final class WorkerMain {

    private static final Duration HEARTBEAT = Duration.ofSeconds(15);
    private static final long SELF_STOP_MILLIS = 45_000;
    private static final long RUN_CAP_BYTES = 1L << 30;
    static final int EXIT_SELF_STOPPED = 3;
    private static final String AUTH_EXCEPTION = "environment-login";
    private static final String OTP_EXCEPTION = "environment-login-code";
    private static final String RESEND_EXCEPTION = "environment-login-code-resend";
    /** Exploration numbers its steps from 1 and roots them at 0, so sign-in sits apart and never joins a reproduction path. */
    private static final int SIGN_IN_STEP = -1;
    private static final int SIGN_IN_AGAIN_STEP = -2;
    private static final int DISCOVERY_SCREENSHOTS = 50;
    private static final long PAUSE_MILLIS = 20;

    private WorkerMain() {}

    public static void main(String[] args) throws Exception {
        String base = System.getenv("QA_INTERNAL_URL");
        String key = Files.readString(Path.of(System.getenv("QA_WORKER_KEY_FILE"))).trim();
        String proxy = System.getenv("QA_EGRESS_PROXY");
        String workerId = InetAddress.getLocalHost().getHostName();
        PlatformClient platform = new PlatformClient(base, key, InternalTls.load(Path.of(System.getenv("QA_TLS_DIR"))));

        JobAssignment job = null;
        while (job == null) {
            try {
                job = platform.claim(workerId).orElse(null);
            } catch (IOException e) {
                System.err.println("platform not reachable, retrying");
            }
            if (job == null) Thread.sleep(2000);
        }
        System.out.println("claimed run " + job.runId() + " attempt " + job.attempt());
        run(platform, job, proxy);
        System.exit(0);
    }

    private static void run(PlatformClient platform, JobAssignment job, String proxy) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean lost = new AtomicBoolean();
        AtomicLong lastAck = new AtomicLong(System.currentTimeMillis());
        startHeartbeat(platform, cancelled, lost, lastAck);
        platform.start();

        LoginSecret secret = job.login() == null ? null : platform.login();
        ExecutionPolicy policy = policy(job);
        ActionGate gate = new ActionGate(policy, Clock.systemUTC(), line -> { });
        EvidenceSink sink = (type, step, content) -> upload(platform, type, step, content);
        Redactor redactor = new Redactor(knownSecrets(secret));
        Sanitizer sanitizer = new Sanitizer(redactor);
        EvidenceCollector evidence = new EvidenceCollector(sink, redactor, RUN_CAP_BYTES);

        if ("API".equals(job.channel())) {
            runApi(platform, job, gate, sanitizer, evidence, secret, proxy, cancelled, lost);
            return;
        }

        BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true).setChromiumSandbox(true);
        if (proxy != null && !proxy.isBlank()) {
            launch.setProxy(new Proxy(proxy).setBypass("<-loopback>"));
            launch.setArgs(List.of("--proxy-bypass-list=<-loopback>", "--disable-quic"));
        }

        try (PlaywrightBrowser browser = new PlaywrightBrowser(gate, launch)) {
            ExplorationEngine engine = new ExplorationEngine(gate, browser, new VerdictEngine(policy.scope()), evidence, job.seed(), Clock.systemUTC(), job.maskSelectors())
                    .stopWhen(() -> cancelled.get() || lost.get())
                    .onProgress(step -> call(() -> platform.steps(List.of(sanitizer.step(step)))), o -> call(() -> platform.finding(sanitizer.finding(o))));
            boolean test = WorkerContracts.AUTH_TEST.equals(job.kind());
            if (job.login() != null) {
                LoginFlow flow = new LoginFlow(gate, browser);
                LoginFlow.Spec spec = spec(job);
                LoginFlow.Credentials credentials = credentials(secret);
                LoginFlow.Attempt attempt = flow.attempt(spec, credentials, new LoginFlow.Evidence(evidence, SIGN_IN_STEP), new PlatformCodes(platform, redactor, 1));
                record(platform, sanitizer, job, attempt.result(), false, 1);
                if (attempt.outcome() != LoginFlow.Outcome.SIGNED_IN) {
                    String reason = test ? attempt.result().failure().name() : attempt.outcome() == LoginFlow.Outcome.BLOCKED ? "AUTH_BLOCKED" : "AUTH_FAILED";
                    platform.complete(new RunCompletion("FAILED", reason, 1, java.util.Map.of()));
                    return;
                }
                if (test) {
                    platform.complete(new RunCompletion("SUCCEEDED", "AUTHENTICATED", 1, java.util.Map.of()));
                    return;
                }
                engine.sessionEndsAt(job.login().loginPath());
                if (job.login().successPath() != null) engine.seed(base(job) + job.login().successPath());
                // The session lives only in this job's browser. When it ends, the run checks it really has, then signs in
                // again once, in the same browser, with the credentials it already holds. The gate's attempt limit applies.
                engine.keepSessionWith(() -> {
                    if (flow.stillSignedIn(spec)) return ExplorationEngine.Restore.STILL_SIGNED_IN;
                    LoginFlow.Attempt again = flow.attempt(spec, credentials, new LoginFlow.Evidence(evidence, SIGN_IN_AGAIN_STEP), new PlatformCodes(platform, redactor, 2));
                    record(platform, sanitizer, job, again.result(), false, 2);
                    return again.outcome() == LoginFlow.Outcome.SIGNED_IN ? ExplorationEngine.Restore.SIGNED_IN_AGAIN : ExplorationEngine.Restore.FAILED;
                });
            } else if (test) {
                var connected = new LoginFlow(gate, browser).connect(job.startUrl(), new LoginFlow.Evidence(evidence, SIGN_IN_STEP));
                record(platform, sanitizer, job, connected, true, 1);
                platform.complete(new RunCompletion(connected.failure() == null ? "SUCCEEDED" : "FAILED", connected.failure() == null ? "CONNECTED" : connected.failure().name(), 1, java.util.Map.of()));
                return;
            }
            boolean discovery = WorkerContracts.DISCOVERY.equals(job.kind());
            if (discovery) {
                // Each new page state is read while it is on screen, before anything on it is used, and reported at once.
                // Screenshots for the first pages only: they are the largest evidence, and the inventory is the point.
                int[] screenshots = {0};
                List<String> masks = new java.util.ArrayList<>(List.of("input[type=password]", "input[autocomplete=one-time-code]"));
                masks.addAll(job.maskSelectors());
                engine.onNewState((step, depth, snapshot) -> {
                    var inventory = browser.describePage();
                    if (inventory == null) return;
                    call(() -> platform.discoveredPage(sanitizer.page(snapshot.fingerprint(), step, depth, inventory)));
                    if (screenshots[0]++ < DISCOVERY_SCREENSHOTS) evidence.binary(qaengine.core.evidence.EvidenceType.SCREENSHOT, step, browser.screenshot(masks));
                });
            }
            RunResult result = job.scope() == null ? engine.explore(job.startUrl()) : engine.visitOnly(job.scope().urls());
            if (discovery && !lost.get()) {
                var edges = result.transitions().stream().filter(t -> !t.from().isEmpty())
                        .map(t -> new WorkerContracts.DiscoveredEdge(t.from(), t.to(), redactor.redact(t.via()))).toList();
                for (int i = 0; i < edges.size(); i += 500) {
                    var batch = edges.subList(i, Math.min(edges.size(), i + 500));
                    call(() -> platform.discoveredEdges(batch));
                }
            }
            if (lost.get()) return;
            String outcome = result.stop() == RunResult.StopReason.CANCELLED ? "CANCELLED" : "SUCCEEDED";
            platform.complete(new RunCompletion(outcome, result.stop().name(), result.steps().size(), result.states()));
        } catch (PlatformClient.LostLease e) {
            System.err.println("lease lost; stopping");
        } catch (java.io.UncheckedIOException e) {
            // The platform stayed away too long. That says nothing about the target, so do not
            // report a failure: stop, and let lease recovery retry the run.
            System.err.println("platform unreachable; leaving the job for lease recovery");
        } catch (RuntimeException e) {
            System.err.println("run failed: " + e.getClass().getSimpleName());
            if (!lost.get()) platform.complete(new RunCompletion("FAILED", e.getClass().getSimpleName(), 0, java.util.Map.of()));
        }
    }

    /** No browser here: requests go straight through the egress proxy, after the same gate. */
    private static void runApi(PlatformClient platform, JobAssignment job, ActionGate gate, Sanitizer sanitizer, EvidenceCollector evidence,
                               LoginSecret secret, String proxy, AtomicBoolean cancelled, AtomicBoolean lost) throws Exception {
        try {
            if (secret != null) ExecutionLock.requireSecureForSecrets(job.startUrl());
            var profile = job.api();
            var selector = proxy == null || proxy.isBlank() ? null : ProxySelector.of(proxyAddress(proxy));
            var client = new ApiClient(gate, job.startUrl(), selector);
            var config = new ApiRunner.Config(profile.specPath(), secret == null ? null : secret.password(), secret == null ? null : secret.lowPrivilegeToken(), PAUSE_MILLIS, profile.seedPaths(),
                    job.scope() == null ? null : java.util.Set.copyOf(job.scope().labels()));
            RunResult result = new ApiRunner(gate, client, new ApiVerdictEngine(profile.slowMillis()), evidence, config)
                    .stopWhen(() -> cancelled.get() || lost.get())
                    .onProgress(step -> call(() -> platform.steps(List.of(sanitizer.step(step)))), o -> call(() -> platform.finding(sanitizer.finding(o))))
                    .run();
            if (lost.get()) return;
            String outcome = result.stop() == RunResult.StopReason.CANCELLED ? "CANCELLED" : "SUCCEEDED";
            platform.complete(new RunCompletion(outcome, result.stop().name(), result.steps().size(), java.util.Map.of()));
        } catch (PlatformClient.LostLease e) {
            System.err.println("lease lost; stopping");
        } catch (java.io.UncheckedIOException e) {
            System.err.println("platform unreachable; leaving the job for lease recovery");
        } catch (RuntimeException e) {
            System.err.println("run failed: " + e.getClass().getSimpleName());
            if (!lost.get()) platform.complete(new RunCompletion("FAILED", e.getClass().getSimpleName(), 0, java.util.Map.of()));
        }
    }

    private static InetSocketAddress proxyAddress(String proxy) {
        var uri = java.net.URI.create(proxy);
        return new InetSocketAddress(uri.getHost(), uri.getPort());
    }

    private static List<String> knownSecrets(LoginSecret s) {
        if (s == null) return List.of();
        return java.util.stream.Stream.of(s.password(), s.oneTimeCode(), s.sessionCookieValue(), s.lowPrivilegeToken()).filter(java.util.Objects::nonNull).toList();
    }

    private static String base(JobAssignment job) {
        return job.startUrl().replaceAll("/+$", "");
    }

    private static LoginFlow.Spec spec(JobAssignment job) {
        var profile = job.login();
        LoginForm form;
        try {
            form = new LoginForm(locator(profile.usernameField()), locator(profile.passwordField()), locator(profile.submitButton()),
                    locator(profile.successElement()), locator(profile.failureElement()), profile.timeoutSeconds());
        } catch (RuntimeException e) {
            // The platform validates these; a profile it let through but this cannot read is not guessed around.
            form = LoginForm.automatic();
        }
        OtpStep otp = null;
        if (profile.otp() != null) {
            try {
                var o = profile.otp();
                otp = new OtpStep(locator(o.field()), locator(o.submitButton()), locator(o.failureElement()), locator(o.resendButton()), o.maxResends(),
                        Duration.ofSeconds(o.waitSeconds()), OTP_EXCEPTION, o.resendPath() == null ? null : RESEND_EXCEPTION);
            } catch (RuntimeException e) {
                otp = null; // Without a usable code step the flow stops with a clear reason rather than guessing.
            }
        }
        return new LoginFlow.Spec(LoginFlow.Method.valueOf(profile.method()), base(job), profile.loginPath() == null ? "" : profile.loginPath(), profile.successPath(), AUTH_EXCEPTION,
                form, otp);
    }

    private static LoginFlow.Credentials credentials(LoginSecret s) {
        return new LoginFlow.Credentials(s.username(), s.password(), s.oneTimeCode(), s.sessionCookieName(), s.sessionCookieValue());
    }

    private static LocatorSpec locator(WorkerContracts.Locator l) {
        return l == null ? null : new LocatorSpec(LocatorSpec.Strategy.valueOf(l.strategy()), l.value(), l.name());
    }

    /** Sign-in shows in the step log as its own step, before any exploration step, and its evidence hangs off it. */
    /** @param signIn 1 for the run's sign-in, 2 for signing in again; each gets its own step before exploration's */
    private static void record(PlatformClient platform, Sanitizer sanitizer, JobAssignment job, AuthenticationResult r, boolean connectionOnly, int signIn) {
        var verdict = r.failure() == null ? qaengine.core.verdict.Verdict.PASS
                : r.failure() == AuthenticationResult.Failure.POLICY_REFUSED || r.failure() == AuthenticationResult.Failure.ATTEMPT_LIMIT ? qaengine.core.verdict.Verdict.BLOCKED
                : qaengine.core.verdict.Verdict.FAIL;
        String url = connectionOnly || job.login() == null ? job.startUrl() : base(job) + (job.login().loginPath() == null ? "" : job.login().loginPath());
        int sequence = signIn == 1 ? SIGN_IN_STEP : SIGN_IN_AGAIN_STEP;
        var step = new qaengine.core.engine.StepRecord(sequence, sequence, qaengine.core.policy.ActionRequest.Kind.FORM_SUBMIT, url,
                connectionOnly ? "Connect" : signIn == 1 ? "Sign in" : "Sign in again after the session ended", List.of(), verdict, 0, "", r.detail(), r.duration().toMillis());
        call(() -> platform.steps(List.of(sanitizer.step(step))));
        call(() -> platform.auth(sanitizer.auth(r, connectionOnly, signIn)));
    }

    private static ExecutionPolicy policy(JobAssignment job) {
        var limits = job.limits();
        TargetScope scope = new TargetScope(Set.copyOf(job.allowedOrigins()), job.includePaths(), job.excludePaths());
        RunBudget budget = new RunBudget(limits.maxActions(), limits.maxPages(), limits.maxDepth(), Duration.ofMinutes(limits.maxMinutes()), limits.maxLoginAttempts());
        List<ScopedException> exceptions = new java.util.ArrayList<>();
        if (job.login() != null && job.login().submitPath() != null && !"API".equals(job.channel())) {
            Instant until = Instant.now().plus(Duration.ofMinutes(limits.maxMinutes()));
            String origin = job.allowedOrigins().getFirst();
            exceptions.add(new ScopedException(AUTH_EXCEPTION, ScopedException.Purpose.AUTH, origin, "POST", job.login().submitPath(), null,
                    until, limits.maxLoginAttempts(), "environment login profile", ScopedException.ApproverRole.ORGANIZATION_ADMIN));
            var otp = job.login().otp();
            // The code and the resend each get their own narrow permission, used only inside the sign-in window.
            if (otp != null && otp.submitPath() != null) {
                exceptions.add(new ScopedException(OTP_EXCEPTION, ScopedException.Purpose.AUTH, origin, "POST", otp.submitPath(), null,
                        until, limits.maxLoginAttempts(), "environment login profile", ScopedException.ApproverRole.ORGANIZATION_ADMIN));
            }
            if (otp != null && otp.resendPath() != null && otp.maxResends() > 0) {
                exceptions.add(new ScopedException(RESEND_EXCEPTION, ScopedException.Purpose.AUTH, origin, "POST", otp.resendPath(), null,
                        until, otp.maxResends() * limits.maxLoginAttempts(), "environment login profile", ScopedException.ApproverRole.ORGANIZATION_ADMIN));
            }
        }
        return new ExecutionPolicy(ExecutionMode.valueOf(job.mode()), EnvironmentClass.valueOf(job.environmentClass()), scope, exceptions, new HashSet<>(), budget);
    }

    private static void startHeartbeat(PlatformClient platform, AtomicBoolean cancelled, AtomicBoolean lost, AtomicLong lastAck) {
        Thread.ofVirtual().name("heartbeat").start(() -> {
            while (true) {
                try {
                    Thread.sleep(HEARTBEAT);
                    if (platform.heartbeat()) cancelled.set(true);
                    lastAck.set(System.currentTimeMillis());
                } catch (PlatformClient.LostLease e) {
                    lost.set(true);
                    return;
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    if (System.currentTimeMillis() - lastAck.get() > SELF_STOP_MILLIS) {
                        System.err.println("no heartbeat acknowledgement; self-stopping");
                        Runtime.getRuntime().halt(EXIT_SELF_STOPPED);
                    }
                }
            }
        });
    }

    private static EvidenceRef upload(PlatformClient platform, EvidenceType type, int step, byte[] content) {
        String sha = LocalEvidenceSink.sha256(content);
        try {
            platform.evidence(step, type.name(), sha, content);
            return new EvidenceRef(type, step, sha, content.length, "platform", EvidenceRef.Status.STORED);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private interface Call { void run() throws IOException, InterruptedException; }

    private static void call(Call c) {
        try {
            c.run();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
