package qaengine.fixtures;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** The planted-defect REST API. Clean endpoints stay clean, so a false finding is detectable. */
final class ApiRoutes {

    static final String JSON = "application/json";
    private static final String STACK = "java.lang.NullPointerException: value\\n\\tat com.acme.Search.run(Search.java:42)";

    private ApiRoutes() {}

    static boolean handles(String path) {
        return path.startsWith("/api/v1") || path.equals("/openapi.json");
    }

    static void route(HttpExchange x, String path) throws IOException {
        String method = x.getRequestMethod();
        Map<String, String> q = query(x.getRequestURI().getRawQuery());
        if (method.equals("OPTIONS")) { options(x); return; }
        if (path.equals("/openapi.json")) { spec(x); return; }
        if (path.equals("/api/v1")) { json(x, 200, "{\"links\":{\"items\":\"/api/v1/items\",\"status\":\"/api/v1/status\",\"public\":\"/api/v1/public\"}}"); return; }
        if (!method.equals("GET") && !method.equals("HEAD")) { json(x, 200, "{\"mutated\":true}"); return; }

        switch (path) {
            case "/api/v1/items" -> {
                Integer limit = number(q.getOrDefault("limit", "5"));
                if (limit == null || limit < 1 || limit > 100) { json(x, 400, "{\"error\":\"invalid limit\"}"); return; }
                json(x, 200, "{\"items\":[{\"id\":1,\"name\":\"Lamp\",\"price\":9.5}]}");
            }
            case "/api/v1/search" -> {
                String term = q.get("q");
                if (term == null || term.isEmpty()) { json(x, 400, "{\"error\":\"q required\"}"); return; }
                if (term.length() > 64) { json(x, 500, "{\"error\":\"" + STACK + "\"}"); return; }
                json(x, 200, "{\"items\":[]}");
            }
            case "/api/v1/status" -> send(x, 200, "text/html", "<p>ok</p>", true);
            case "/api/v1/me" -> {
                if (role(x) == null) { json(x, 401, "{\"error\":\"unauthorized\"}"); return; }
                json(x, 200, "{\"user\":\"" + role(x) + "\"}");
            }
            case "/api/v1/admin/report" -> {
                String role = role(x);
                if (role == null) { json(x, 401, "{\"error\":\"unauthorized\"}"); return; }
                if (!role.equals("admin")) { json(x, 403, "{\"error\":\"forbidden\"}"); return; }
                json(x, 200, "{\"ok\":true}");
            }
            case "/api/v1/admin/users" -> json(x, 200, "{\"ok\":true}");
            case "/api/v1/admin/audit" -> {
                if (role(x) == null) { json(x, 401, "{\"error\":\"unauthorized\"}"); return; }
                json(x, 200, "{\"ok\":true}");
            }
            case "/api/v1/limits" -> {
                Integer n = number(q.getOrDefault("n", ""));
                if (n == null || n < 1) { json(x, 400, "{\"error\":\"invalid n\"}"); return; }
                json(x, 200, "{\"ok\":true}");
            }
            case "/api/v1/debug" -> {
                String mode = q.getOrDefault("mode", "");
                if (mode.equals("a") || mode.equals("b")) { json(x, 200, "{\"ok\":true}"); return; }
                json(x, 400, "{\"error\":\"" + STACK + "\"}");
            }
            case "/api/v1/slow" -> {
                try { Thread.sleep(1200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                json(x, 200, "{\"ok\":true}");
            }
            case "/api/v1/public" -> send(x, 200, JSON, "{\"ok\":true}", false);
            case "/api/v1/moved" -> {
                x.getResponseHeaders().add("Location", "http://blocked.invalid/moved");
                x.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
                x.sendResponseHeaders(302, -1);
            }
            case "/api/v1/items/purge" -> json(x, 200, "{\"purged\":true}");
            default -> byId(x, path);
        }
    }

    private static void byId(HttpExchange x, String path) throws IOException {
        String[] parts = path.split("/");
        String last = parts[parts.length - 1];
        Integer id = number(last);
        if (path.startsWith("/api/v1/items/")) {
            if (id == null || id < 1) { json(x, 400, "{\"error\":\"invalid id\"}"); return; }
            if (id > 1000) { json(x, 404, "{\"error\":\"not found\"}"); return; }
            json(x, 200, "{\"id\":" + id + ",\"name\":\"Lamp\",\"price\":9.5}");
        } else if (path.startsWith("/api/v1/orders/")) {
            if (id == null || id < 1) { json(x, 400, "{\"error\":\"invalid id\"}"); return; }
            json(x, 200, "{\"id\":" + id + ",\"total\":\"12.50\"}");
        } else if (path.startsWith("/api/v1/reports/")) {
            if (id == null || id < 1) { json(x, 400, "{\"error\":\"invalid id\"}"); return; }
            json(x, 202, "{\"ok\":true}");
        } else {
            json(x, 404, "{\"error\":\"not found\"}");
        }
    }

    private static String role(HttpExchange x) {
        String auth = x.getRequestHeaders().getFirst("Authorization");
        if (("Bearer " + FixtureServer.API_ADMIN_TOKEN).equals(auth)) return "admin";
        if (("Bearer " + FixtureServer.API_USER_TOKEN).equals(auth)) return "user";
        return null;
    }

    private static void options(HttpExchange x) throws IOException {
        x.getResponseHeaders().add("Allow", "GET, HEAD, OPTIONS");
        x.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        x.sendResponseHeaders(204, -1);
    }

    private static void spec(HttpExchange x) throws IOException {
        try (InputStream in = ApiRoutes.class.getResourceAsStream("/openapi.json")) {
            send(x, 200, JSON, new String(in.readAllBytes(), StandardCharsets.UTF_8), true);
        }
    }

    private static void json(HttpExchange x, int status, String body) throws IOException {
        send(x, status, JSON, body, true);
    }

    private static void send(HttpExchange x, int status, String type, String body, boolean nosniff) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", type);
        if (nosniff) x.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        boolean head = x.getRequestMethod().equals("HEAD");
        x.sendResponseHeaders(status, head ? -1 : bytes.length);
        if (!head) x.getResponseBody().write(bytes);
    }

    private static Integer number(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(kv[0], kv.length == 2 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }
}
