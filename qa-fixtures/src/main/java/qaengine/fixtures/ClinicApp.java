package qaengine.fixtures;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A second, unrelated application on the fixture, under /clinic: patient records with its own sign-in, a searchable
 * and paginated list, records with tabs, a booking form with validation, and a reports area. It shares nothing with
 * the storefront but the server, so a test that passes on both was not written for either.
 *
 * <p>Planted: the monthly visits report answers 500. Everything else behaves as it says.
 */
final class ClinicApp {

    static final String COOKIE = "clinic_session";
    private static final int PAGE_SIZE = 5;

    record Patient(int id, String name, String born, boolean active, String lastVisit) {}
    record Visit(int id, int patient, String date, String reason, String clinician) {}

    static final List<Patient> PATIENTS = List.of(
            new Patient(1, "Amal Haddad", "1984-03-12", true, "2026-10-02"),
            new Patient(2, "Brian Okafor", "1990-07-21", true, "2026-09-18"),
            new Patient(3, "Carla Rivera", "1975-11-05", false, "2025-12-01"),
            new Patient(4, "Daniel Kim", "2001-01-30", true, "2026-11-01"),
            new Patient(5, "Elena Petrova", "1968-06-14", true, "2026-08-22"),
            new Patient(6, "Farid Nasser", "1993-02-08", false, "2024-05-17"),
            new Patient(7, "Grace Liu", "1988-09-27", true, "2026-10-29"),
            new Patient(8, "Hassan Ali", "1979-12-03", true, "2026-07-11"));

    static final List<Visit> VISITS = List.of(
            new Visit(101, 1, "2026-10-02", "Annual check-up", "Dr. Moreau"),
            new Visit(102, 1, "2026-04-15", "Blood test review", "Dr. Moreau"),
            new Visit(103, 2, "2026-09-18", "Sprained ankle", "Dr. Osei"),
            new Visit(104, 4, "2026-11-01", "Vaccination", "Dr. Osei"),
            new Visit(105, 7, "2026-10-29", "Follow-up", "Dr. Moreau"));

    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();

    boolean handles(String path) {
        return path.equals("/clinic") || path.startsWith("/clinic/");
    }

    void route(HttpExchange x, String path, String body) throws IOException {
        switch (path) {
            case "/clinic/login" -> { send(x, 200, login(null)); return; }
            case "/clinic/session" -> { session(x, body); return; }
            case "/clinic/logout" -> {
                String token = cookie(x);
                if (token != null) sessions.remove(token);
                x.getResponseHeaders().add("Set-Cookie", COOKIE + "=gone; Max-Age=0; Path=/clinic");
                redirect(x, "/clinic/login");
                return;
            }
            default -> { }
        }
        String token = cookie(x);
        if (token == null || !sessions.contains(token)) { redirect(x, "/clinic/login"); return; }
        Map<String, String> q = query(x.getRequestURI().getRawQuery());
        if (path.equals("/clinic") || path.equals("/clinic/")) { send(x, 200, page("Dashboard", dashboard())); return; }
        if (path.equals("/clinic/patients")) { send(x, 200, page("Patients", patients(q))); return; }
        if (path.startsWith("/clinic/patients/")) { patient(x, path.substring("/clinic/patients/".length())); return; }
        if (path.startsWith("/clinic/visits/")) { visit(x, path.substring("/clinic/visits/".length())); return; }
        if (path.equals("/clinic/appointments")) { send(x, 200, page("Appointments", appointments(q))); return; }
        if (path.equals("/clinic/appointments/new")) { send(x, 200, page("Book an appointment", booking())); return; }
        if (path.equals("/clinic/reports")) { send(x, 200, page("Reports", reports())); return; }
        if (path.equals("/clinic/reports/invoices")) { send(x, 200, page("Outstanding invoices", invoices())); return; }
        if (path.equals("/clinic/reports/monthly")) { send(x, 500, "<!doctype html><title>Error</title><h1>Something went wrong</h1>"); return; }
        send(x, 404, page("Not found", "<p>There is no such page.</p>" + nav()));
    }

    // ---- sign-in

    private static String login(String error) {
        return "<!doctype html><html lang=en><head><meta charset=utf-8><title>Clinic sign in</title></head><body><main><h1>Sign in to Clinic records</h1>"
                + (error == null ? "" : "<p role=alert>" + error + "</p>")
                + "<form method=post action=\"/clinic/session\">"
                + "<label for=email>Email</label><input id=email name=email type=text autocomplete=username required>"
                + "<label for=password>Password</label><input id=password name=password type=password autocomplete=current-password required>"
                + "<button type=submit>Sign in</button></form></main></body></html>";
    }

    private void session(HttpExchange x, String body) throws IOException {
        Map<String, String> f = query(body);
        if (!FixtureServer.TEST_USER.equals(f.get("email")) || !FixtureServer.TEST_PASSWORD.equals(f.get("password"))) {
            send(x, 200, login("Incorrect email or password."));
            return;
        }
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        sessions.add(token);
        x.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; HttpOnly; SameSite=Strict; Path=/clinic");
        redirect(x, "/clinic/");
    }

    // ---- pages

    private static String nav() {
        return "<nav aria-label=\"Main\"><ul>"
                + "<li><a href=\"/clinic/\">Dashboard</a></li><li><a href=\"/clinic/patients\">Patients</a></li>"
                + "<li><a href=\"/clinic/appointments\">Appointments</a></li><li><a href=\"/clinic/appointments/new\">New appointment</a></li>"
                + "<li><a href=\"/clinic/reports\">Reports</a></li><li><a href=\"/clinic/logout\">Sign out</a></li></ul></nav>";
    }

    private static String page(String title, String content) {
        return "<!doctype html><html lang=en><head><meta charset=utf-8><title>" + esc(title) + " - Clinic records</title></head><body>"
                + "<header><p>Clinic records</p>" + nav() + "</header><main><h1>" + esc(title) + "</h1>" + content + "</main></body></html>";
    }

    private static String dashboard() {
        long active = PATIENTS.stream().filter(Patient::active).count();
        return "<section aria-labelledby=today><h2 id=today>Today</h2><p>" + active + " active patients. " + VISITS.size() + " recent visits.</p></section>"
                + "<section aria-labelledby=shortcuts><h2 id=shortcuts>Shortcuts</h2><ul>"
                + "<li><a href=\"/clinic/patients?status=active\">Active patients</a></li>"
                + "<li><a href=\"/clinic/appointments?range=week\">This week's appointments</a></li></ul></section>";
    }

    private static String patients(Map<String, String> q) {
        String term = q.getOrDefault("q", "").strip().toLowerCase(Locale.ROOT);
        String status = q.getOrDefault("status", "");
        int pageNo = Math.max(1, parse(q.get("page"), 1));
        List<Patient> matching = PATIENTS.stream()
                .filter(p -> term.isEmpty() || p.name().toLowerCase(Locale.ROOT).contains(term))
                .filter(p -> status.isEmpty() || status.equals("all") || (status.equals("active") == p.active()))
                .toList();
        int pages = Math.max(1, (matching.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        pageNo = Math.min(pageNo, pages);
        List<Patient> shown = matching.subList((pageNo - 1) * PAGE_SIZE, Math.min(matching.size(), pageNo * PAGE_SIZE));
        StringBuilder b = new StringBuilder();
        b.append("<form role=search method=get action=\"/clinic/patients\" aria-label=\"Find patients\">")
                .append("<label for=q>Search patients</label><input id=q name=q type=search value=\"").append(esc(q.getOrDefault("q", ""))).append("\">")
                .append("<label for=status>Status</label><select id=status name=status>")
                .append(option("all", "All", status)).append(option("active", "Active", status)).append(option("inactive", "Inactive", status))
                .append("</select><button type=submit>Search</button></form>");
        if (shown.isEmpty()) {
            b.append("<p role=status>No patients match.</p>");
        } else {
            b.append("<p role=status>").append(matching.size()).append(" patients</p>")
                    .append("<table><caption>Patient list</caption><thead><tr><th scope=col>Name</th><th scope=col>Date of birth</th><th scope=col>Status</th><th scope=col>Last visit</th></tr></thead><tbody>");
            for (Patient p : shown) {
                b.append("<tr><td><a href=\"/clinic/patients/").append(p.id()).append("\">").append(esc(p.name())).append("</a></td><td>")
                        .append(p.born()).append("</td><td>").append(p.active() ? "Active" : "Inactive").append("</td><td>").append(p.lastVisit()).append("</td></tr>");
            }
            b.append("</tbody></table>");
        }
        String keep = (term.isEmpty() ? "" : "&q=" + enc(q.get("q"))) + (status.isEmpty() ? "" : "&status=" + enc(status));
        b.append("<nav aria-label=\"Pages\"><p>Page ").append(pageNo).append(" of ").append(pages).append("</p>");
        if (pageNo > 1) b.append("<a href=\"/clinic/patients?page=").append(pageNo - 1).append(keep).append("\">Previous page</a> ");
        if (pageNo < pages) b.append("<a href=\"/clinic/patients?page=").append(pageNo + 1).append(keep).append("\">Next page</a>");
        return b.append("</nav>").toString();
    }

    private static String option(String value, String label, String selected) {
        return "<option value=\"" + value + "\"" + (value.equals(selected) ? " selected" : "") + ">" + label + "</option>";
    }

    private void patient(HttpExchange x, String id) throws IOException {
        Patient p = PATIENTS.stream().filter(c -> String.valueOf(c.id()).equals(id)).findFirst().orElse(null);
        if (p == null) { send(x, 404, page("Not found", "<p>There is no such patient.</p>")); return; }
        StringBuilder visits = new StringBuilder();
        List<Visit> mine = VISITS.stream().filter(v -> v.patient() == p.id()).toList();
        if (mine.isEmpty()) {
            visits.append("<p>No visits recorded.</p>");
        } else {
            visits.append("<table><caption>Visits</caption><thead><tr><th scope=col>Date</th><th scope=col>Reason</th><th scope=col>Clinician</th></tr></thead><tbody>");
            for (Visit v : mine) visits.append("<tr><td><a href=\"/clinic/visits/").append(v.id()).append("\">").append(v.date()).append("</a></td><td>")
                    .append(esc(v.reason())).append("</td><td>").append(esc(v.clinician())).append("</td></tr>");
            visits.append("</tbody></table>");
        }
        String content = "<div role=tablist aria-label=\"Record\">"
                + tab("overview", "Overview", true) + tab("visits", "Visits", false) + tab("billing", "Billing", false) + "</div>"
                + panel("overview", "<dl><dt>Date of birth</dt><dd>" + p.born() + "</dd><dt>Status</dt><dd>" + (p.active() ? "Active" : "Inactive") + "</dd></dl>", true)
                + panel("visits", visits.toString(), false)
                + panel("billing", "<p>No outstanding balance.</p><button type=button>Cancel appointment</button>", false)
                + "<p><a href=\"/clinic/patients\">Back to patients</a></p>"
                + "<script>for (const t of document.querySelectorAll('[role=tab]')) t.addEventListener('click', () => {"
                + " for (const o of document.querySelectorAll('[role=tab]')) { o.setAttribute('aria-selected', String(o === t));"
                + " document.getElementById(o.getAttribute('aria-controls')).hidden = o !== t; } });</script>";
        send(x, 200, page(p.name(), content));
    }

    private static String tab(String id, String label, boolean selected) {
        return "<button type=button role=tab id=tab-" + id + " aria-controls=panel-" + id + " aria-selected=" + selected + ">" + label + "</button>";
    }

    private static String panel(String id, String content, boolean shown) {
        return "<section role=tabpanel id=panel-" + id + " aria-labelledby=tab-" + id + (shown ? "" : " hidden") + ">" + content + "</section>";
    }

    private void visit(HttpExchange x, String id) throws IOException {
        Visit v = VISITS.stream().filter(c -> String.valueOf(c.id()).equals(id)).findFirst().orElse(null);
        if (v == null) { send(x, 404, page("Not found", "<p>There is no such visit.</p>")); return; }
        send(x, 200, page("Visit on " + v.date(), "<dl><dt>Reason</dt><dd>" + esc(v.reason()) + "</dd><dt>Clinician</dt><dd>" + esc(v.clinician()) + "</dd></dl>"
                + "<p><a href=\"/clinic/patients/" + v.patient() + "\">Back to patient</a></p>"));
    }

    private static String appointments(Map<String, String> q) {
        String range = q.getOrDefault("range", "all");
        return "<p><a href=\"/clinic/appointments?range=today\">Today</a> <a href=\"/clinic/appointments?range=week\">This week</a> "
                + "<a href=\"/clinic/appointments\">All</a></p><p role=status>Showing " + esc(range.equals("all") ? "all appointments" : range.equals("today") ? "today" : "this week") + "</p>"
                + "<table><caption>Appointments</caption><thead><tr><th scope=col>Date</th><th scope=col>Patient</th><th scope=col>Clinician</th><th scope=col>Status</th></tr></thead><tbody>"
                + "<tr><td>2026-11-13</td><td>Amal Haddad</td><td>Dr. Moreau</td><td>Booked</td></tr>"
                + (range.equals("today") ? "" : "<tr><td>2026-11-16</td><td>Grace Liu</td><td>Dr. Osei</td><td>Booked</td></tr>")
                + "</tbody></table>";
    }

    private static String booking() {
        StringBuilder options = new StringBuilder("<option value=\"\">Choose a patient</option>");
        for (Patient p : PATIENTS) if (p.active()) options.append("<option value=\"").append(p.id()).append("\">").append(esc(p.name())).append("</option>");
        return "<ol aria-label=\"Steps\"><li aria-current=step>Details</li><li>Time</li><li>Review</li></ol>"
                + "<form method=post action=\"/clinic/appointments\" aria-label=\"Appointment details\">"
                + "<label for=patient>Patient</label><select id=patient name=patient required>" + options + "</select>"
                + "<label for=date>Preferred date</label><input id=date name=date type=date required>"
                + "<label for=reminder>Reminder email</label><input id=reminder name=reminder type=email>"
                + "<label for=reason>Reason for visit</label><textarea id=reason name=reason required minlength=5></textarea>"
                + "<button type=submit>Continue</button></form>";
    }

    private static String reports() {
        return "<ul><li><a href=\"/clinic/reports/invoices\">Outstanding invoices</a></li><li><a href=\"/clinic/reports/monthly\">Monthly visits</a></li></ul>";
    }

    private static String invoices() {
        return "<table><caption>Outstanding invoices</caption><thead><tr><th scope=col>Invoice</th><th scope=col>Patient</th><th scope=col>Amount</th></tr></thead>"
                + "<tbody><tr><td>INV-2041</td><td>Brian Okafor</td><td>120.00</td></tr><tr><td>INV-2044</td><td>Hassan Ali</td><td>80.00</td></tr></tbody></table>";
    }

    // ---- helpers

    private static int parse(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String cookie(HttpExchange x) {
        List<String> headers = new ArrayList<>(x.getRequestHeaders().getOrDefault("Cookie", List.of()));
        for (String header : headers) {
            for (String part : header.split(";\\s*")) if (part.startsWith(COOKIE + "=")) return part.substring(COOKIE.length() + 1);
        }
        return null;
    }

    private static void send(HttpExchange x, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }

    private static void redirect(HttpExchange x, String location) throws IOException {
        x.getResponseHeaders().set("Location", location);
        x.sendResponseHeaders(302, -1);
    }
}
