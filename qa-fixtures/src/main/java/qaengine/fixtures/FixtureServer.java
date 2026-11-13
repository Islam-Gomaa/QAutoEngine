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
    public static final String TEST_TOTP = "654321";
    public static final String API_ADMIN_TOKEN = "fixture-admin-token-canary-K9";
    public static final String API_USER_TOKEN = "fixture-user-token-canary-M3";

    private final HttpServer server;
    private final ExecutorService pool;
    private final List<Recorded> log = new CopyOnWriteArrayList<>();
    /** A second application on the same server, with nothing in common with the storefront. */
    private final ClinicApp clinic = new ClinicApp();
    /** A third, built unlike both: no sign-in, every page drawn by a script from JSON. */
    private final HelpdeskApp desk = new HelpdeskApp();
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
        if (clinic.handles(path)) { clinic.route(x, path, body); return; }
        if (desk.handles(path)) { desk.route(x, path); return; }
        if (ApiRoutes.handles(path)) { ApiRoutes.route(x, path); return; }
        if (path.startsWith("/app/")) { app(x, path, body); return; }
        if (path.equals("/crash") || path.equals("/api/fail")) { send(x, 500, "text/html", "<h1>Server error</h1>"); return; }
        if (path.startsWith("/m/") || path.equals("/graphql")) { send(x, 200, "application/json", "{}"); return; }
        if (path.equals("/redirect-internal")) { redirect(x, "/m/redirect-target"); return; }
        if (path.equals("/redirect-chain")) { redirect(x, "/redirect-internal"); return; }
        if (path.equals("/redirect-external")) { redirect(x, "http://blocked.invalid/landing"); return; }
        if (path.equals("/calendar")) { calendar(x); return; }
        if (path.equals("/session")) { login(x, body); return; }
        if (path.equals("/session-totp")) { loginTotp(x, body); return; }
        if (path.equals("/session-push")) { redirect(x, "/mfa-wait"); return; }
        if (path.equals("/account") || path.equals("/account/profile")) { account(x, path); return; }
        if (path.equals("/account/expire")) { expire(x); return; }
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

    // An application area that behaves like a modern product: labelled fields, a re-rendered form with an
    // alert on a wrong password, pages behind sign-in, and variants that are hard to classify on purpose.

    /** The session cookie of the application area. Separate from the older /account pages. Each sign-in gets its own token. */
    public static final String APP_COOKIE = "app_session";

    /** Live sessions of the application area, by token, with how many signed-in pages each has served. */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> appSessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger appSignIns = new java.util.concurrent.atomic.AtomicInteger();
    private volatile int appSessionPageLimit = Integer.MAX_VALUE;

    /** Ends each application session after it has served this many signed-in pages, as a session timeout would. */
    public void appSessionPageLimit(int pages) {
        appSessionPageLimit = pages;
    }

    /** How many times anyone has signed in to the application area. */
    public int appSignIns() {
        return appSignIns.get();
    }

    /** The tokens of the sessions that are still alive. */
    public java.util.Set<String> liveAppSessions() {
        return java.util.Set.copyOf(appSessions.keySet());
    }

    private void startAppSession(HttpExchange x) {
        String token = Long.toHexString(RANDOM.nextLong()) + Long.toHexString(RANDOM.nextLong());
        appSessions.put(token, new java.util.concurrent.atomic.AtomicInteger());
        appSignIns.incrementAndGet();
        x.getResponseHeaders().add("Set-Cookie", APP_COOKIE + "=" + token + "; HttpOnly; Path=/app");
    }

    /** Counts a signed-in page view; a session past its limit is forgotten, as it would be on a real server. */
    private boolean appSession(HttpExchange x) {
        String token = cookieValue(x, APP_COOKIE);
        var views = token == null ? null : appSessions.get(token);
        if (views == null) return false;
        if (views.incrementAndGet() > appSessionPageLimit) {
            appSessions.remove(token);
            return false;
        }
        return true;
    }

    private static String loginPage(String action, String error) {
        return "<!doctype html><title>Sign in</title><main><h1>Sign in to Fixture</h1>"
                + (error == null ? "" : "<p role=\"alert\" data-testid=\"sign-in-error\">" + error + "</p>")
                + "<form method=\"post\" action=\"" + action + "\">"
                + "<label for=\"email\">Email</label> <input id=\"email\" name=\"email\" type=\"text\" autocomplete=\"username\">"
                + "<label for=\"pw\">Password</label> <input id=\"pw\" name=\"password\" type=\"password\" autocomplete=\"current-password\">"
                + "<button type=\"submit\" data-testid=\"sign-in\">Sign in</button></form></main>";
    }

    private static final String SPA_LOGIN = "<!doctype html><title>Sign in</title><main><h1>Sign in</h1>"
            + "<p id=\"err\" role=\"alert\" hidden>Incorrect email or password.</p>"
            + "<form><label for=\"email\">Email</label> <input id=\"email\" name=\"email\">"
            + "<label for=\"pw\">Password</label> <input id=\"pw\" name=\"password\" type=\"password\">"
            + "<button type=\"submit\">Sign in</button></form></main><script>"
            + "document.querySelector('form').addEventListener('submit', async e => { e.preventDefault();"
            + " const r = await fetch('/app/api/session', {method: 'POST', body: new URLSearchParams(new FormData(e.target))});"
            + " if (r.ok) location.href = '/app/home'; else document.getElementById('err').hidden = false; });</script>";

    /** Exact field values: a password that merely starts with the right one is wrong. */
    private static boolean validAppLogin(String body) {
        java.util.Map<String, String> form = new java.util.HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) form.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8), java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return TEST_USER.equals(form.get("email")) && TEST_PASSWORD.equals(form.get("password"));
    }

    private static String signedIn(String title, String content) {
        return "<!doctype html><title>" + title + "</title><header><nav aria-label=\"Account\">"
                + "<button type=\"button\" data-testid=\"account-menu\" aria-label=\"Account menu\">Fixture User</button> <a href=\"/app/logout\">Sign out</a></nav></header>"
                + "<main><h1>" + title + "</h1>" + content + "</main>";
    }

    // One-time codes. The fixture "emails" a code by recording it: tests read it as a mailbox reader would.

    /** A code the fixture sent, and until when it is accepted. A resend or a use ends it. */
    record Otp(String code, long expiresAtMillis) {}

    private final java.util.concurrent.ConcurrentHashMap<String, Otp> pendingOtp = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> outbox = new CopyOnWriteArrayList<>();
    private volatile long otpLifetimeMillis = 120_000;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /** Every code sent, oldest first, as they would arrive in a mailbox. */
    public List<String> sentCodes() {
        return List.copyOf(outbox);
    }

    /** Makes codes sent from now on expire after this many milliseconds. */
    public void otpLifetime(long millis) {
        otpLifetimeMillis = millis;
    }

    private String sendCode(String pending) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        pendingOtp.put(pending, new Otp(code, System.currentTimeMillis() + otpLifetimeMillis));
        outbox.add(code);
        return code;
    }

    private static String otpPage(String error, boolean boxes) {
        String inputs = boxes
                ? java.util.stream.IntStream.rangeClosed(1, 6).mapToObj(i -> "<input name=\"d" + i + "\" maxlength=\"1\" inputmode=\"numeric\" aria-label=\"Digit " + i + "\" class=\"digit\">").collect(java.util.stream.Collectors.joining())
                : "<label for=\"otp\">One-time code</label> <input id=\"otp\" name=\"otp\" autocomplete=\"one-time-code\" inputmode=\"numeric\">";
        return "<!doctype html><title>Enter your code</title><main><h1>Check your email</h1>"
                + (error == null ? "" : "<p role=\"alert\" data-testid=\"otp-error\">" + error + "</p>")
                + "<form method=\"post\" action=\"/app/otp-verify\">" + inputs + " <button type=\"submit\">Verify</button></form>"
                + "<form method=\"post\" action=\"/app/otp-resend\"><button type=\"submit\" data-testid=\"resend\">Send a new code</button></form></main>";
    }

    private static java.util.Map<String, String> form(String body) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) out.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8), java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String cookieValue(HttpExchange x, String name) {
        String cookie = x.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";\\s*")) if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        return null;
    }

    private void otp(HttpExchange x, String path, String body) throws IOException {
        String pending = cookieValue(x, "otp_pending");
        boolean boxes = "boxes".equals(cookieValue(x, "otp_style"));
        switch (path) {
            case "/app/login-otp", "/app/login-otp-boxes" -> {
                x.getResponseHeaders().add("Set-Cookie", "otp_style=" + (path.endsWith("boxes") ? "boxes" : "single") + "; Path=/app");
                send(x, 200, "text/html; charset=utf-8", loginPage("/app/session-otp", null));
            }
            case "/app/session-otp" -> {
                if (!validAppLogin(body)) { send(x, 200, "text/html; charset=utf-8", loginPage("/app/session-otp", "Incorrect email or password.")); return; }
                String token = Long.toHexString(RANDOM.nextLong());
                sendCode(token);
                x.getResponseHeaders().add("Set-Cookie", "otp_pending=" + token + "; HttpOnly; Path=/app");
                redirect(x, "/app/otp");
            }
            case "/app/otp" -> {
                if (pending == null || !pendingOtp.containsKey(pending)) { redirect(x, "/app/login-otp"); return; }
                send(x, 200, "text/html; charset=utf-8", otpPage(null, boxes));
            }
            case "/app/otp-resend" -> {
                if (pending == null || !pendingOtp.containsKey(pending)) { redirect(x, "/app/login-otp"); return; }
                sendCode(pending);
                redirect(x, "/app/otp");
            }
            case "/app/otp-verify" -> {
                Otp expected = pending == null ? null : pendingOtp.get(pending);
                var f = form(body);
                String given = boxes ? java.util.stream.IntStream.rangeClosed(1, 6).mapToObj(i -> f.getOrDefault("d" + i, "")).collect(java.util.stream.Collectors.joining()) : f.getOrDefault("otp", "");
                if (expected == null) { redirect(x, "/app/login-otp"); return; }
                if (!expected.code().equals(given) || System.currentTimeMillis() > expected.expiresAtMillis()) {
                    send(x, 200, "text/html; charset=utf-8", otpPage("That code is not valid. It may have expired.", boxes));
                    return;
                }
                pendingOtp.remove(pending); // single use
                startAppSession(x);
                redirect(x, "/app/home");
            }
            default -> send(x, 404, "text/html", "<h1>Not found</h1>");
        }
    }

    // A signed-in admin console, for discovery: forms with required and unlabelled fields, tables, a closed dialog,
    // tabs, pagination, and actions of every safety class. Nothing under it may ever be submitted or followed
    // when it changes state; the tests check what reached the server.

    private static final String CONSOLE_NAV = "<nav aria-label=\"Console\"><a href=\"/app/console\">Console</a> <a href=\"/app/console/users\">Users</a> "
            + "<a href=\"/app/console/billing\">Billing</a> <a href=\"/app/console/settings\">Settings</a></nav>";

    private static String console(String title, String content) {
        return "<!doctype html><title>" + title + "</title><header><button type=\"button\" data-testid=\"account-menu\" aria-label=\"Account menu\">Fixture User</button>"
                + CONSOLE_NAV + "</header><main><h1>" + title + "</h1>" + content + "</main>";
    }

    private static final java.util.Map<String, String> CONSOLE = java.util.Map.of(
            "/app/console", console("Console", "<h2>Overview</h2><p>Two people are waiting for an invitation.</p><a href=\"/app/console/users\">Manage users</a>"),
            "/app/console/users", console("Users",
                    "<div role=\"tablist\" aria-label=\"Status\"><button role=\"tab\" aria-selected=\"true\">Active</button><button role=\"tab\" aria-selected=\"false\">Invited</button></div>"
                    + "<table><caption>Team members</caption><thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Actions</th></tr></thead><tbody>"
                    + "<tr><td>Amal</td><td>amal@shop.example</td><td>Admin</td><td><a href=\"/app/console/users/1/edit\">Edit</a></td></tr>"
                    + "<tr><td>Badr</td><td>badr@shop.example</td><td>Viewer</td><td><a href=\"/app/console/users/2/delete\">Remove</a></td></tr>"
                    + "<tr><td>Carmen</td><td>carmen@shop.example</td><td>Editor</td><td><button type=\"button\" data-testid=\"delete-user\" data-confirm=\"Delete this user?\">Delete user</button></td></tr>"
                    + "</tbody></table><a href=\"/app/console/users?page=2\">Next page</a>"
                    + "<h2>Invite a teammate</h2><form method=\"post\" action=\"/app/console/invitations\" aria-label=\"Invite a teammate\">"
                    + "<label for=\"invite-email\">Email</label> <input id=\"invite-email\" name=\"email\" type=\"email\" required>"
                    + "<label for=\"invite-role\">Role</label> <select id=\"invite-role\" name=\"role\"><option>Viewer</option><option>Editor</option></select>"
                    + "<button type=\"submit\">Invite user</button></form>"
                    + "<dialog aria-label=\"Remove user\"><h2>Remove user</h2><p>This cannot be undone.</p><button>Remove</button> <button>Close</button></dialog>"),
            "/app/console/users/1/edit", console("Edit user",
                    "<form method=\"post\" action=\"/app/console/users/1\" aria-label=\"Edit user\"><label for=\"name\">Name</label> <input id=\"name\" name=\"name\" required value=\"Amal\">"
                    + "<button type=\"submit\">Save changes</button></form><a href=\"/app/console/users\">Back to users</a>"),
            "/app/console/billing", console("Billing",
                    "<table aria-label=\"Invoices\"><tr><th>Invoice</th><th>Amount</th></tr><tr><td>INV-7</td><td>40.00</td></tr></table>"
                    + "<a href=\"/app/console/invoices/7.pdf\" download>Download invoice</a>"
                    + "<form method=\"post\" action=\"/app/console/payments\"><button type=\"submit\">Pay invoice</button></form>"
                    + "<button type=\"button\">Update card</button>"),
            "/app/console/settings", console("Settings",
                    "<form method=\"post\" action=\"/app/console/settings\" aria-label=\"Profile\">"
                    + "<label for=\"display\">Display name</label> <input id=\"display\" name=\"display\" required>"
                    + "<label for=\"mail\">Email</label> <input id=\"mail\" name=\"mail\" type=\"email\" required>"
                    + "<label><input type=\"checkbox\" name=\"news\"> Product news</label>"
                    + "<label for=\"tz\">Timezone</label> <select id=\"tz\" name=\"tz\"><option>UTC</option></select>"
                    + "<input name=\"nickname\" placeholder=\"Nickname\">"
                    + "<button type=\"submit\">Save changes</button></form>"));

    private void consolePage(HttpExchange x, String path) throws IOException {
        if (!appSession(x)) { redirect(x, "/app/login"); return; }
        String page = CONSOLE.get(path);
        if (page == null) { send(x, 404, "text/html", "<h1>Not found</h1>"); return; }
        send(x, 200, "text/html; charset=utf-8", page);
    }

    private void app(HttpExchange x, String path, String body) throws IOException {
        if (path.startsWith("/app/console")) { consolePage(x, path); return; }
        if (path.startsWith("/app/login-otp") || path.equals("/app/session-otp") || path.startsWith("/app/otp")) { otp(x, path, body); return; }
        switch (path) {
            case "/app/login" -> send(x, 200, "text/html; charset=utf-8", loginPage("/app/session", null));
            case "/app/login-limbo" -> send(x, 200, "text/html; charset=utf-8", loginPage("/app/session-limbo", null));
            case "/app/login-spa" -> send(x, 200, "text/html; charset=utf-8", SPA_LOGIN);
            case "/app/login-offsite" -> redirect(x, "http://login.elsewhere.invalid/sign-in");
            case "/app/login-moved" -> redirect(x, "/app/login");
            case "/app/login-broken" -> send(x, 500, "text/html", "<h1>Server error</h1>");
            case "/app/session" -> {
                if (validAppLogin(body)) {
                    startAppSession(x);
                    redirect(x, "/app/home");
                } else {
                    send(x, 200, "text/html; charset=utf-8", loginPage("/app/session", "Incorrect email or password."));
                }
            }
            // Accepts the password but never starts a session: only the success checks can tell.
            case "/app/session-limbo" -> redirect(x, "/app/limbo");
            case "/app/limbo" -> send(x, 200, "text/html", "<!doctype html><title>Almost there</title><main><h1>Something went wrong</h1><a href=\"/app/login\">Sign in</a></main>");
            case "/app/api/session" -> {
                if (validAppLogin(body)) {
                    startAppSession(x);
                    x.sendResponseHeaders(204, -1);
                } else {
                    send(x, 401, "application/json", "{\"error\":\"invalid\"}");
                }
            }
            case "/app/logout" -> {
                String token = cookieValue(x, APP_COOKIE);
                if (token != null) appSessions.remove(token);
                x.getResponseHeaders().add("Set-Cookie", "app_session=gone; Max-Age=0; Path=/app");
                redirect(x, "/app/login");
            }
            // A single-page app that notices the session is gone and sends the browser to its login page itself.
            case "/app/reports" -> send(x, 200, "text/html; charset=utf-8", appSession(x)
                    ? signedIn("Reports", "<p>Monthly reports.</p><a href=\"/app/home\">Home</a>")
                    : "<!doctype html><title>Reports</title><script>location.replace('/app/login')</script>");
            // An API-style page that answers 401 once the session is gone.
            case "/app/team" -> {
                if (appSession(x)) send(x, 200, "text/html; charset=utf-8", signedIn("Team", "<p>Three members.</p><a href=\"/app/home\">Home</a>"));
                else send(x, 401, "text/html", "<title>Sign in required</title><p>Sign in required.</p>");
            }
            case "/app/home", "/app/orders", "/app/settings" -> {
                if (!appSession(x)) { redirect(x, "/app/login"); return; }
                String content = switch (path) {
                    case "/app/orders" -> "<table><caption>Recent orders</caption><tr><th>Order</th><th>Total</th></tr><tr><td>1001</td><td>12.00</td></tr></table><a href=\"/app/home\">Home</a>";
                    case "/app/settings" -> "<form><label for=\"n\">Display name</label> <input id=\"n\" name=\"name\" required></form><a href=\"/app/home\">Home</a>";
                    default -> "<p>Welcome back.</p><a href=\"/app/orders\">Orders</a> <a href=\"/app/settings\">Settings</a> <a href=\"/app/reports\">Reports</a> <a href=\"/app/team\">Team</a>";
                };
                send(x, 200, "text/html; charset=utf-8", signedIn(path.equals("/app/home") ? "Welcome back" : path.equals("/app/orders") ? "Orders" : "Settings", content));
            }
            default -> send(x, 404, "text/html", "<h1>Not found</h1>");
        }
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

    private void loginTotp(HttpExchange x, String body) throws IOException {
        if (body.contains("password=" + TEST_PASSWORD) && body.contains("code=" + TEST_TOTP)) {
            x.getResponseHeaders().add("Set-Cookie", "session=fixture-session; HttpOnly; Path=/");
            redirect(x, "/account");
            return;
        }
        send(x, 401, "text/html", "<title>Login failed</title>");
    }

    private void account(HttpExchange x, String path) throws IOException {
        String cookie = x.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || !cookie.contains("session=fixture-session")) { redirect(x, "/login"); return; }
        if (path.equals("/account/profile")) {
            send(x, 200, "text/html", "<title>Profile</title><a href=\"/account\">Back</a> <a href=\"/account/expire\">Refresh session</a>");
            return;
        }
        send(x, 200, "text/html", "<title>Your Account</title><a href=\"/account/profile\">Profile</a> <a href=\"/logout\">Sign out</a>");
    }

    private void expire(HttpExchange x) throws IOException {
        x.getResponseHeaders().add("Set-Cookie", "session=gone; Max-Age=0; Path=/");
        redirect(x, "/login");
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
