package qaengine.fixtures;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local site with planted defects and traps. It binds to loopback only and records every request,
 * so results are judged by what reached the server rather than by what a driver claims.
 */
public final class FixtureServer implements AutoCloseable {

    /** One request as seen by the server. */
    public record Recorded(String method, String path, String query, String body) {
        public String target() { return query == null ? path : path + "?" + query; }
    }

    public static final String TEST_USER = "fixture-user";
    public static final String TEST_PASSWORD = "fixture-canary-password-7Q2x";

    private final HttpServer server;
    private final ExecutorService pool;
    private final List<Recorded> log = new CopyOnWriteArrayList<>();
    boolean exposeLog;

    private FixtureServer(HttpServer server, ExecutorService pool) {
        this.server = server;
        this.pool = pool;
    }

    public static FixtureServer start() throws IOException {
        return start(0);
    }

    public static FixtureServer start(int port) throws IOException {
        return start(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }

    /** Used by {@link FixtureMain} on the isolated test network. Everything else stays on loopback. */
    static FixtureServer start(InetSocketAddress address) throws IOException {
        HttpServer http = HttpServer.create(address, 0);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        FixtureServer fixture = new FixtureServer(http, pool);
        http.createContext("/", fixture::handle);
        http.setExecutor(pool);
        http.start();
        return fixture;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String url(String path) {
        return baseUrl() + path;
    }

    public List<Recorded> requests() {
        return new ArrayList<>(log);
    }

    public void clear() {
        log.clear();
    }

    public boolean reached(String pathPrefix) {
        return log.stream().anyMatch(r -> r.target().startsWith(pathPrefix));
    }

    private void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        if (exposeLog && path.equals("/__requests")) {
            try (x) { send(x, 200, "text/plain", String.join("\n", log.stream().map(r -> r.method() + " " + r.target()).toList())); }
            return;
        }
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        log.add(new Recorded(x.getRequestMethod(), path, x.getRequestURI().getRawQuery(), body));
        try (x) {
            route(x, path, body);
        }
    }

    private void route(HttpExchange x, String path, String body) throws IOException {
        if (path.equals("/crash") || path.equals("/api/fail")) { send(x, 500, "text/html", "<h1>Server error</h1>"); return; }
        if (path.startsWith("/m/") || path.equals("/graphql")) { send(x, 200, "application/json", "{}"); return; }
        if (path.equals("/redirect-internal")) { redirect(x, "/m/redirect-target"); return; }
        if (path.equals("/redirect-chain")) { redirect(x, "/redirect-internal"); return; }
        if (path.equals("/redirect-external")) { redirect(x, "http://blocked.invalid/landing"); return; }
        if (path.equals("/calendar")) { calendar(x); return; }
        if (path.equals("/session")) { login(x, body); return; }
        if (path.equals("/account")) { account(x); return; }
        if (path.equals("/refresh")) { send(x, 200, "application/json", "{\"ok\":true}"); return; }
        if (path.equals("/sw.js")) {
            send(x, 200, "application/javascript", "self.addEventListener('install',e=>self.skipWaiting());"
                    + "self.addEventListener('activate',e=>e.waitUntil(clients.claim()));"
                    + "self.addEventListener('fetch',e=>e.respondWith(fetch(e.request)));");
            return;
        }
        if (path.equals("/worker.js")) {
            send(x, 200, "application/javascript", "fetch('/m/webworker-post',{method:'POST'}).catch(()=>{});");
            return;
        }
        if (path.startsWith("/items/") || path.startsWith("/verify/") || path.equals("/logout") || path.equals("/tools/nightly")) {
            send(x, 200, "text/html", "<title>Done</title><a href=\"/\">Home</a>");
            return;
        }
        if (path.startsWith("/download/")) {
            x.getResponseHeaders().add("Content-Disposition", "attachment; filename=report.bin");
            send(x, 200, "application/octet-stream", "x");
            return;
        }
        String page = Pages.ALL.get(path);
        if (page == null) { send(x, 404, "text/html", "<h1>Not found</h1>"); return; }
        send(x, 200, "text/html; charset=utf-8", page);
    }

    private void calendar(HttpExchange x) throws IOException {
        String q = x.getRequestURI().getQuery();
        int month = q != null && q.startsWith("month=") ? Integer.parseInt(q.substring(6)) : 1;
        send(x, 200, "text/html", "<title>Calendar " + month + "</title><a href=\"/calendar?month=" + (month + 1) + "\">Next</a>");
    }

    private void login(HttpExchange x, String body) throws IOException {
        if (body.contains("username=" + TEST_USER) && body.contains("password=" + TEST_PASSWORD)) {
            x.getResponseHeaders().add("Set-Cookie", "session=fixture-session; HttpOnly; Path=/");
            redirect(x, "/account");
            return;
        }
        send(x, 401, "text/html", "<title>Login failed</title><a href=\"/login\">Retry</a>");
    }

    private void account(HttpExchange x) throws IOException {
        String cookie = x.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || !cookie.contains("session=fixture-session")) { redirect(x, "/login"); return; }
        send(x, 200, "text/html", "<title>Your Account</title><a href=\"/account/profile\">Profile</a> <a href=\"/logout\">Sign out</a>");
    }

    private void redirect(HttpExchange x, String location) throws IOException {
        x.getResponseHeaders().add("Location", location);
        x.sendResponseHeaders(302, -1);
    }

    private void send(HttpExchange x, int status, String type, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", type);
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
        pool.close();
    }
}
