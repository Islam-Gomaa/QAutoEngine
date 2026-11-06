package qaengine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qaengine.api.ApiClient.Exchange;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.LocalEvidenceSink;
import qaengine.core.evidence.Redactor;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionMode;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.RunBudget;
import qaengine.core.policy.TargetScope;

/** A spec and its responses are attacker-controlled. These try to use them against the worker. */
class HostileSpecTest {

    /** Stands in for an internal service the worker must never be tricked into calling. */
    private static HttpServer canary(AtomicInteger hits) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/", x -> { hits.incrementAndGet(); byte[] b = "{\"type\":\"object\"}".getBytes(); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        s.start();
        return s;
    }

    private static ApiOperation opWithSchema(String schemaJson) {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"/a\":{\"get\":{\"responses\":{\"200\":{\"description\":\"\",\"content\":{\"application/json\":{\"schema\":" + schemaJson + "}}}}}}}}";
        return OpenApiReader.read(spec).getFirst();
    }

    private static void evaluate(ApiOperation op) {
        var x = new Exchange("GET", "http://t/a", 200, Map.of("content-type", "application/json", "x-content-type-options", "nosniff"), "{\"a\":1}", 1, Exchange.Outcome.RESPONDED, "");
        new ApiVerdictEngine(1000).evaluate(new ApiScenario(ApiScenario.Kind.POSITIVE, op, Map.of(), Map.of(), ApiScenario.Credential.NONE), x);
    }

    @Test
    void schemaCannotMakeTheValidatorFetchAUrl() throws Exception {
        var hits = new AtomicInteger();
        var s = canary(hits);
        try {
            String url = "http://127.0.0.1:" + s.getAddress().getPort();
            for (String keyword : List.of("$schema", "$id", "$dynamicRef", "$recursiveRef")) {
                evaluate(opWithSchema("{\"" + keyword + "\":\"" + url + "/x\",\"type\":\"object\"}"));
                evaluate(opWithSchema("{\"type\":\"object\",\"properties\":{\"a\":{\"" + keyword + "\":\"" + url + "/y\"}}}"));
            }
            assertEquals(0, hits.get(), "the validator contacted a URL taken from the spec");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void realPropertiesNamedLikeKeywordsSurvive() {
        var op = opWithSchema("{\"type\":\"object\",\"required\":[\"id\"],\"properties\":{\"id\":{\"type\":\"integer\"}}}");
        assertTrue(op.responseFor(200).schema().path("properties").has("id"));
    }

    @Test
    void schemaErrorsDoNotEchoResponseData() {
        var op = opWithSchema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"enum\":[\"x\"]}}}");
        var x = new Exchange("GET", "http://t/a", 200, Map.of("content-type", "application/json", "x-content-type-options", "nosniff"), "{\"a\":\"personal-data-canary\"}", 1, Exchange.Outcome.RESPONDED, "");
        var e = new ApiVerdictEngine(1000).evaluate(new ApiScenario(ApiScenario.Kind.POSITIVE, op, Map.of(), Map.of(), ApiScenario.Credential.NONE), x);
        assertFalse(e.findings().isEmpty());
        assertTrue(e.findings().stream().noneMatch(f -> f.detail().contains("personal-data-canary")), e.findings().toString());
    }

    @Test
    void oneRootCauseIsOneDefect() {
        var op = OpenApiReader.read("{\"openapi\":\"3.0.0\",\"paths\":{\"/a\":{\"get\":{\"security\":[{\"b\":[]}],\"responses\":{\"200\":{\"description\":\"\"}}}}}}").getFirst();
        var x = new Exchange("GET", "http://t/a", 200, Map.of(), "{}", 1, Exchange.Outcome.RESPONDED, "");
        var engine = new ApiVerdictEngine(1000);
        var none = engine.evaluate(new ApiScenario(ApiScenario.Kind.NO_CREDENTIAL, op, Map.of(), Map.of(), ApiScenario.Credential.NONE), x);
        var bad = engine.evaluate(new ApiScenario(ApiScenario.Kind.INVALID_CREDENTIAL, op, Map.of(), Map.of(), ApiScenario.Credential.INVALID), x);
        assertEquals(none.findings().getFirst().fingerprint(), bad.findings().getFirst().fingerprint());
    }

    @Test
    void reflectedTokenIsRedactedFromEvidence(@TempDir Path dir) throws Exception {
        String token = "reflected-token-canary-77";
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"/echo\":{\"get\":{\"security\":[{\"b\":[]}],\"responses\":{\"200\":{\"description\":\"\"},\"401\":{\"description\":\"\"}}}}}}";
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/", x -> {
            String path = x.getRequestURI().getPath();
            String auth = String.valueOf(x.getRequestHeaders().getFirst("Authorization"));
            byte[] b = (path.equals("/openapi.json") ? spec : "{\"debug\":\"" + auth + "\"}").getBytes();
            int status = path.equals("/echo") ? 500 : path.equals("/openapi.json") ? 200 : 404;
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(status, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        s.start();
        try {
            String base = "http://127.0.0.1:" + s.getAddress().getPort();
            var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, TargetScope.of(base), List.of(), Set.of(), RunBudget.defaults());
            var gate = new ActionGate(policy, Clock.systemUTC(), l -> { });
            var r = new ApiRunner(gate, new ApiClient(gate, base, null), new ApiVerdictEngine(1000),
                    new EvidenceCollector(new LocalEvidenceSink(dir), new Redactor(List.of(token)), 1L << 30), new ApiRunner.Config(null, token, null, 0, List.of())).run();
            assertFalse(r.observations().isEmpty());
            assertFalse(r.toString().contains(token), "result");
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) assertFalse(Files.readString(f).contains(token), f.toString());
            }
        } finally {
            s.stop(0);
        }
    }
}
