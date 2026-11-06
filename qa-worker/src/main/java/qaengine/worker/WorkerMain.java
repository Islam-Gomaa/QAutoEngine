package qaengine.worker;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.options.Proxy;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import qaengine.contracts.WorkerContracts.FindingReport;
import qaengine.contracts.WorkerContracts.JobAssignment;
import qaengine.contracts.WorkerContracts.RunCompletion;
import qaengine.contracts.WorkerContracts.StepReport;
import qaengine.core.engine.ExplorationEngine;
import qaengine.core.engine.Observation;
import qaengine.core.engine.RunResult;
import qaengine.core.engine.StepRecord;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.EvidenceRef;
import qaengine.core.evidence.EvidenceSink;
import qaengine.core.evidence.EvidenceType;
import qaengine.core.evidence.LocalEvidenceSink;
import qaengine.core.evidence.Redactor;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionMode;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.RunBudget;
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

    private WorkerMain() {}

    public static void main(String[] args) throws Exception {
        String base = System.getenv("QA_INTERNAL_URL");
        String key = Files.readString(Path.of(System.getenv("QA_WORKER_KEY_FILE"))).trim();
        String proxy = System.getenv("QA_EGRESS_PROXY");
        String workerId = InetAddress.getLocalHost().getHostName();
        PlatformClient platform = new PlatformClient(base, key);

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

        ExecutionPolicy policy = policy(job);
        ActionGate gate = new ActionGate(policy, Clock.systemUTC(), line -> { });
        EvidenceSink sink = (type, step, content) -> upload(platform, type, step, content);
        EvidenceCollector evidence = new EvidenceCollector(sink, new Redactor(List.of()), RUN_CAP_BYTES);

        BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true).setChromiumSandbox(true);
        if (proxy != null && !proxy.isBlank()) {
            launch.setProxy(new Proxy(proxy).setBypass("<-loopback>"));
            launch.setArgs(List.of("--proxy-bypass-list=<-loopback>", "--disable-quic"));
        }

        try (PlaywrightBrowser browser = new PlaywrightBrowser(gate, launch)) {
            RunResult result = new ExplorationEngine(gate, browser, new VerdictEngine(policy.scope()), evidence, job.seed(), Clock.systemUTC(), job.maskSelectors())
                    .stopWhen(() -> cancelled.get() || lost.get())
                    .onProgress(step -> report(platform, step), o -> report(platform, o))
                    .explore(job.startUrl());
            if (lost.get()) return;
            String outcome = result.stop() == RunResult.StopReason.CANCELLED ? "CANCELLED" : "SUCCEEDED";
            platform.complete(new RunCompletion(outcome, result.stop().name(), result.steps().size(), result.states()));
        } catch (PlatformClient.LostLease e) {
            System.err.println("lease lost; stopping");
        } catch (RuntimeException e) {
            System.err.println("run failed: " + e.getClass().getSimpleName());
            if (!lost.get()) platform.complete(new RunCompletion("FAILED", e.getClass().getSimpleName(), 0, java.util.Map.of()));
        }
    }

    private static ExecutionPolicy policy(JobAssignment job) {
        var limits = job.limits();
        TargetScope scope = new TargetScope(Set.copyOf(job.allowedOrigins()), job.includePaths(), job.excludePaths());
        RunBudget budget = new RunBudget(limits.maxActions(), limits.maxPages(), limits.maxDepth(), Duration.ofMinutes(limits.maxMinutes()), limits.maxLoginAttempts());
        return new ExecutionPolicy(ExecutionMode.valueOf(job.mode()), EnvironmentClass.valueOf(job.environmentClass()), scope, List.of(), new HashSet<>(), budget);
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

    private static void report(PlatformClient platform, StepRecord s) {
        call(() -> platform.steps(List.of(new StepReport(s.sequence(), s.parent(), s.kind().name(), s.url(), s.label(), s.locators(),
                s.verdict().name(), s.status(), s.state(), s.note()))));
    }

    private static void report(PlatformClient platform, Observation o) {
        var f = o.finding();
        var shas = o.evidence().stream().filter(e -> e.status() == EvidenceRef.Status.STORED).map(EvidenceRef::sha256).toList();
        call(() -> platform.finding(new FindingReport(o.step(), f.fingerprint(), f.type().name(), f.severity().name(), f.url(), f.title(), f.detail(), shas)));
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
