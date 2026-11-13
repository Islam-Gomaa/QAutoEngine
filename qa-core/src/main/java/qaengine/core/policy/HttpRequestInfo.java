package qaengine.core.policy;

import java.util.Map;

/** An outgoing request as seen by the browser-level or client-level guard. */
public record HttpRequestInfo(String method, String url, Map<String, String> headers, String body, Resource resource) {

    /** What the request is for. Static assets are exempt from URL token checks only. */
    public enum Resource { DOCUMENT, FETCH, STATIC, WEBSOCKET, OTHER }

    public HttpRequestInfo {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static HttpRequestInfo of(String method, String url, Resource resource) {
        return new HttpRequestInfo(method, url, Map.of(), null, resource);
    }
}
