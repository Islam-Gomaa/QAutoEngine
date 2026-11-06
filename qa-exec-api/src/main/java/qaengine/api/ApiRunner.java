package qaengine.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import qaengine.core.engine.Observation;
import qaengine.core.engine.RunResult;
import qaengine.core.engine.StepRecord;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.EvidenceRef;
import qaengine.core.evidence.EvidenceType;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.ActionRequest;
import qaengine.core.policy.Decision;
import qaengine.core.verdict.Finding;
import qaengine.core.verdict.StepEvaluation;
import qaengine.core.verdict.Verdict;
import tools.jackson.databind.json.JsonMapper;

/**
 * Discovers operations, runs generated scenarios and records results in the same form as the web
 * engine, so defects, evidence and test cases work the same. Operations that policy refuses are
 * recorded as blocked coverage, never skipped silently.
 */
public final class ApiRunner {

    /** @param spec path to the spec, or null to search well-known paths */
    public record Config(String spec, String primaryToken, String lowPrivilegeToken, long pauseMillis, List<String> seeds) {
        public Config {
            seeds = seeds == null ? List.of() : List.copyOf(seeds);
        }
    }

    private static final int SLOW_SAMPLES = 3;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ActionGate gate;
    private final ApiClient client;
    private final ApiVerdictEngine verdicts;
    private final EvidenceCollector evidence;
    private final Config config;
    private final List<StepRecord> steps = new ArrayList<>();
    private final Map<String, Observation> observations = new LinkedHashMap<>();
    private BooleanSupplier stop = () -> false;
    private Consumer<StepRecord> stepListener = s -> { };
    private Consumer<Observation> findingListener = o -> { };
    private int sequence;

    public ApiRunner(ActionGate gate, ApiClient client, ApiVerdictEngine verdicts, EvidenceCollector evidence, Config config) {
        this.gate = gate;
        this.client = client;
        this.verdicts = verdicts;
        this.evidence = evidence;
        this.config = config;
    }

    public ApiRunner stopWhen(BooleanSupplier stop) {
        this.stop = stop;
        return this;
    }

    public ApiRunner onProgress(Consumer<StepRecord> steps, Consumer<Observation> findings) {
        this.stepListener = steps;
        this.findingListener = findings;
        return this;
    }

    public RunResult run() {
        List<ApiOperation> operations = discover();
        ScenarioGenerator generator = new ScenarioGenerator(config.primaryToken() != null, config.lowPrivilegeToken() != null);
        RunResult.StopReason reason = RunResult.StopReason.EXHAUSTED;
        operations:
        for (ApiOperation op : operations) {
            for (ApiScenario scenario : generator.generate(op)) {
                if (stop.getAsBoolean()) { reason = RunResult.StopReason.CANCELLED; break operations; }
                Decision allowed = gate.decideAction(new ActionRequest(ActionRequest.Kind.API_CALL, null, Map.of()));
                if (!allowed.allowed()) { reason = RunResult.StopReason.BUDGET; break operations; }
                execute(scenario);
            }
            if (reason == RunResult.StopReason.EXHAUSTED && op.method().equals("GET") && !op.secured()) measure(op);
        }
        return new RunResult(steps, new ArrayList<>(observations.values()), Map.of(), List.of(), reason);
    }

    private List<ApiOperation> discover() {
        Set<String> candidates = new LinkedHashSet<>();
        if (config.spec() != null) candidates.add(config.spec());
        candidates.addAll(ApiClient.wellKnownSpecPaths());
        for (String path : candidates) {
            var x = client.send("GET", path, Map.of("Accept", "application/json"), null);
            record(ActionRequest.Kind.API_CALL, "DISCOVERY GET " + path, x, x.outcome() == ApiClient.Exchange.Outcome.BLOCKED ? Verdict.BLOCKED : x.status() == 200 ? Verdict.PASS : Verdict.INCONCLUSIVE, "");
            if (x.status() != 200) continue;
            try {
                return OpenApiReader.read(x.body());
            } catch (OpenApiReader.SpecRejected e) {
                record(ActionRequest.Kind.API_CALL, "DISCOVERY rejected " + path, x, Verdict.INCONCLUSIVE, e.getMessage());
                return List.of();
            }
        }
        return followLinks();
    }

    /** With no spec, only the configured starting points and links they return are known. */
    private List<ApiOperation> followLinks() {
        Set<String> seen = new LinkedHashSet<>(config.seeds());
        List<String> queue = new ArrayList<>(config.seeds());
        for (int i = 0; i < queue.size() && i < 50; i++) {
            var x = client.send("GET", queue.get(i), Map.of("Accept", "application/json"), null);
            SchemaChecker.parse(x.body()).ifPresent(json -> {
                for (var link : json.path("links").properties()) {
                    String target = link.getValue().asString("");
                    if (target.startsWith("/") && seen.add(target)) queue.add(target);
                }
            });
        }
        List<ApiOperation> out = new ArrayList<>();
        for (String path : seen) {
            out.add(new ApiOperation("GET", path, path, List.of(), Map.of(), false, null, null));
        }
        return out;
    }

    private void execute(ApiScenario scenario) {
        if (pause() && Thread.currentThread().isInterrupted()) return;
        ApiClient.Exchange first = send(scenario);
        StepEvaluation eval = verdicts.evaluate(undocumented(scenario), first);
        if (eval.verdict() == Verdict.FAIL) eval = confirm(scenario, eval);
        int seq = record(ActionRequest.Kind.API_CALL, scenario.label(), first, eval.verdict(), eval.note());
        if (eval.verdict() == Verdict.FAIL) capture(seq, eval.findings(), first);
    }

    private static ApiScenario undocumented(ApiScenario s) {
        return s.operation().responses().isEmpty() ? new ApiScenario(ApiScenario.Kind.UNDOCUMENTED, s.operation(), s.pathValues(), s.queryValues(), s.credential()) : s;
    }

    /** A failure must repeat before it is reported. */
    private StepEvaluation confirm(ApiScenario scenario, StepEvaluation first) {
        StepEvaluation second = verdicts.evaluate(undocumented(scenario), send(scenario));
        Set<String> repeated = new LinkedHashSet<>();
        second.findings().forEach(f -> repeated.add(f.fingerprint()));
        List<Finding> confirmed = first.findings().stream().filter(f -> repeated.contains(f.fingerprint())).toList();
        return confirmed.isEmpty() ? new StepEvaluation(Verdict.INCONCLUSIVE, List.of(), "failure did not repeat") : new StepEvaluation(Verdict.FAIL, confirmed, "");
    }

    private ApiClient.Exchange send(ApiScenario s) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        switch (s.credential()) {
            case PRIMARY -> { if (config.primaryToken() != null) headers.put("Authorization", "Bearer " + config.primaryToken()); }
            case LOW_PRIVILEGE -> headers.put("Authorization", "Bearer " + config.lowPrivilegeToken());
            case INVALID -> headers.put("Authorization", "Bearer invalid-token-for-negative-test");
            case NONE -> { }
        }
        String body = null;
        if (s.operation().requestSchema() != null && !s.operation().requestSchema().isMissingNode() && s.kind() == ApiScenario.Kind.POSITIVE) {
            body = sampleBody(s.operation());
            headers.put("Content-Type", "application/json");
        }
        return client.send(s.method(), ApiClient.expand(s.operation().path(), s.pathValues(), s.queryValues()), headers, body);
    }

    private static String sampleBody(ApiOperation op) {
        var node = JSON.createObjectNode();
        var properties = op.requestSchema().path("properties");
        for (String name : properties.propertyNames()) node.put(name, ScenarioGenerator.validValue(properties.get(name)));
        return node.toString();
    }

    /** The median of several samples, so one slow response does not become a finding. */
    private void measure(ApiOperation op) {
        ScenarioGenerator generator = new ScenarioGenerator(false, false);
        ApiScenario positive = generator.generate(op).stream().filter(s -> s.kind() == ApiScenario.Kind.POSITIVE).findFirst().orElse(null);
        if (positive == null) return;
        long[] samples = new long[SLOW_SAMPLES];
        ApiClient.Exchange last = null;
        for (int i = 0; i < SLOW_SAMPLES; i++) {
            pause();
            last = send(positive);
            if (last.outcome() != ApiClient.Exchange.Outcome.RESPONDED) return;
            samples[i] = last.millis();
        }
        Arrays.sort(samples);
        long median = samples[SLOW_SAMPLES / 2];
        if (!verdicts.isSlow(median)) return;
        int seq = record(ActionRequest.Kind.API_CALL, "RESPONSE_TIME " + op.label(), last, Verdict.FAIL, "median " + median + " ms");
        capture(seq, List.of(verdicts.slow(op, last.url().replaceAll("\\?.*$", ""), median)), last);
    }

    private boolean pause() {
        try {
            if (config.pauseMillis() > 0) Thread.sleep(config.pauseMillis());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private int record(ActionRequest.Kind kind, String label, ApiClient.Exchange x, Verdict verdict, String note) {
        StepRecord step = new StepRecord(++sequence, 0, kind, x.url(), label, List.of(x.method() + " " + x.url()), verdict, x.status(), "", note, x.millis());
        steps.add(step);
        stepListener.accept(step);
        return sequence;
    }

    private void capture(int seq, List<Finding> findings, ApiClient.Exchange x) {
        List<Finding> fresh = findings.stream().filter(f -> !observations.containsKey(f.fingerprint())).toList();
        if (fresh.isEmpty()) return;
        EvidenceRef ref = evidence.text(EvidenceType.API_EXCHANGE, seq, () -> exchangeJson(x));
        for (Finding f : fresh) {
            Observation o = new Observation(f, seq, List.of(ref));
            observations.put(f.fingerprint(), o);
            findingListener.accept(o);
        }
    }

    /** Request credentials are never included, and only harmless response headers are kept. */
    private static String exchangeJson(ApiClient.Exchange x) {
        var node = JSON.createObjectNode();
        node.put("capturedAt", Instant.now().toString());
        node.put("method", x.method());
        node.put("url", x.url());
        node.put("status", x.status());
        node.put("durationMs", x.millis());
        var headers = node.putObject("responseHeaders");
        for (String name : List.of("content-type", "location", "allow", "x-content-type-options")) {
            if (x.header(name) != null) headers.put(name, x.header(name));
        }
        node.put("body", x.body().length() > 65_536 ? x.body().substring(0, 65_536) : x.body());
        return node.toString();
    }
}
