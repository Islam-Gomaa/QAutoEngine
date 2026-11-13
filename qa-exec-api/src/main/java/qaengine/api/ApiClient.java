package qaengine.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.Decision;
import qaengine.core.policy.ExecutionLock;
import qaengine.core.policy.HttpRequestInfo;

/**
 * The only way this module reaches a target. Every request passes the gate first. Redirects are
 * never followed, bodies are capped, and each call has a hard timeout.
 */
public final class ApiClient {

    /** What came back, or why nothing did. */
    public record Exchange(String method, String url, int status, Map<String, String> headers, String body, long millis, Outcome outcome, String detail) {
        public enum Outcome { RESPONDED, BLOCKED, FAILED }

        public static Exchange blocked(String method, String url, String detail) {
            return new Exchange(method, url, 0, Map.of(), "", 0, Outcome.BLOCKED, detail);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    public static final int MAX_BODY = 1 << 20;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "upgrade", "expect");

    private final HttpClient http;
    private final ActionGate gate;
    private final String baseUrl;

    public ApiClient(ActionGate gate, String baseUrl, ProxySelector proxy) {
        ExecutionLock.requireAllowed(gate.policy().scope());
        this.gate = gate;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        var builder = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1);
        if (proxy != null) builder.proxy(proxy);
        this.http = builder.build();
    }

    public static String expand(String path, Map<String, String> pathValues, Map<String, String> query) {
        String out = path;
        for (var e : pathValues.entrySet()) out = out.replace("{" + e.getKey() + "}", encode(e.getValue()));
        if (query.isEmpty()) return out;
        StringBuilder q = new StringBuilder();
        for (var e : new TreeMap<>(query).entrySet()) {
            q.append(q.isEmpty() ? '?' : '&').append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        return out + q;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public Exchange send(String method, String relativeUrl, Map<String, String> headers, String body) {
        String url = baseUrl + relativeUrl;
        Map<String, String> sendHeaders = new LinkedHashMap<>(headers);
        Decision d = gate.decideRequest(new HttpRequestInfo(method, url, sendHeaders, body, HttpRequestInfo.Resource.FETCH));
        if (!d.allowed()) return Exchange.blocked(method, url, d.reason() + ": " + d.detail());

        long start = System.nanoTime();
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            sendHeaders.forEach((k, v) -> {
                if (!RESTRICTED.contains(k.toLowerCase(Locale.ROOT))) request.header(k, v);
            });
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            String text = read(response.body());
            long millis = (System.nanoTime() - start) / 1_000_000;
            Map<String, String> out = new LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), String.join(", ", v)));
            return new Exchange(method, url, response.statusCode(), out, text, millis, Exchange.Outcome.RESPONDED, "");
        } catch (IOException | IllegalArgumentException e) {
            return new Exchange(method, url, 0, Map.of(), "", (System.nanoTime() - start) / 1_000_000, Exchange.Outcome.FAILED, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Exchange(method, url, 0, Map.of(), "", 0, Exchange.Outcome.FAILED, "interrupted");
        }
    }

    /** Reads at most the cap, so a hostile response cannot exhaust memory. */
    private static String read(InputStream in) throws IOException {
        try (in) {
            return new String(in.readNBytes(MAX_BODY), StandardCharsets.UTF_8);
        }
    }

    public static List<String> wellKnownSpecPaths() {
        return List.of("/openapi.json", "/v3/api-docs", "/swagger.json", "/api/openapi.json");
    }
}
