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
import qaengine.contracts.WorkerContracts.JobAssignment;
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
            if (job.login() != null) {
                LoginFlow.Outcome signedIn = signIn(gate, browser, job, secret);
                if (signedIn != LoginFlow.Outcome.SIGNED_IN) {
                    platform.complete(new RunCompletion("FAILED", signedIn == LoginFlow.Outcome.BLOCKED ? "AUTH_BLOCKED" : "AUTH_FAILED", 0, java.util.Map.of()));
                    return;
                }
                engine.sessionEndsAt(job.login().loginPath()).seed(base(job) + job.login().successPath());
            }
            RunResult result = engine.explore(job.startUrl());
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
            var config = new ApiRunner.Config(profile.specPath(), secret == null ? null : secret.password(), secret == null ? null : secret.lowPrivilegeToken(), PAUSE_MILLIS, profile.seedPaths());
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

    private static LoginFlow.Outcome signIn(ActionGate gate, PlaywrightBrowser browser, JobAssignment job, LoginSecret s) {
        var profile = job.login();
        var spec = new LoginFlow.Spec(LoginFlow.Method.valueOf(profile.method()), base(job), profile.loginPath(), profile.successPath(), AUTH_EXCEPTION);
        var credentials = new LoginFlow.Credentials(s.username(), s.password(), s.oneTimeCode(), s.sessionCookieName(), s.sessionCookieValue());
        return new LoginFlow(gate, browser).signIn(spec, credentials);
    }

    private static ExecutionPolicy policy(JobAssignment job) {
        var limits = job.limits();
        TargetScope scope = new TargetScope(Set.copyOf(job.allowedOrigins()), job.includePaths(), job.excludePaths());
        RunBudget budget = new RunBudget(limits.maxActions(), limits.maxPages(), limits.maxDepth(), Duration.ofMinutes(limits.maxMinutes()), limits.maxLoginAttempts());
        List<ScopedException> exceptions = job.login() == null || job.login().submitPath() == null || "API".equals(job.channel()) ? List.of() : List.of(new ScopedException(
                AUTH_EXCEPTION, ScopedException.Purpose.AUTH, job.allowedOrigins().getFirst(), "POST", job.login().submitPath(), null,
                Instant.now().plus(Duration.ofMinutes(limits.maxMinutes())), limits.maxLoginAttempts(), "environment login profile",
                ScopedException.ApproverRole.ORGANIZATION_ADMIN));
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
