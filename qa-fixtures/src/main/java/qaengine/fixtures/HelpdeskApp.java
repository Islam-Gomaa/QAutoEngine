package qaengine.fixtures;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A third application on the fixture, under /desk, built unlike the other two: no sign-in at all, and every page is
 * an empty shell the browser fills in from JSON, so nothing is on the page until a script has run. Tickets sit in a
 * data grid with sortable columns, answers hide in accordions and an expandable panel, "New ticket" opens a dialog
 * from a button, and the ticket form validates its fields. Nothing here shares code or markup with the storefront or
 * the clinic.
 *
 * <p>Planted: the knowledge base links to an article that does not exist.
 */
final class HelpdeskApp {

    record Ticket(int id, String subject, String requester, String status, String priority, String updated) {}

    static final List<Ticket> TICKETS = List.of(
            new Ticket(4101, "Cannot reset password", "noor@example.test", "Open", "High", "2026-11-12"),
            new Ticket(4102, "Invoice shows the wrong address", "sam@example.test", "Pending", "Normal", "2026-11-11"),
            new Ticket(4103, "Export takes too long", "lee@example.test", "Open", "Low", "2026-11-10"),
            new Ticket(4104, "Two-factor code never arrives", "ana@example.test", "Solved", "High", "2026-11-08"));

    boolean handles(String path) {
        return path.equals("/desk") || path.startsWith("/desk/");
    }

    void route(HttpExchange x, String path) throws IOException {
        if (path.equals("/desk/api/tickets")) { json(x, tickets()); return; }
        if (path.startsWith("/desk/api/tickets/")) {
            Ticket t = TICKETS.stream().filter(k -> ("/desk/api/tickets/" + k.id()).equals(path)).findFirst().orElse(null);
            if (t == null) { send(x, 404, "application/json", "{\"error\":\"not found\"}"); return; }
            json(x, ticket(t));
            return;
        }
        if (path.equals("/desk/api/articles")) { json(x, "[{\"slug\":\"passwords\",\"title\":\"Resetting a password\"},{\"slug\":\"billing\",\"title\":\"Changing billing details\"},{\"slug\":\"retired\",\"title\":\"Legacy exports\"}]"); return; }
        if (path.equals("/desk/kb/retired")) { send(x, 404, "text/html; charset=utf-8", "<!doctype html><title>Not found</title><h1>Not found</h1>"); return; }
        // Every other page under /desk is the same shell: the script reads the address and draws the page.
        send(x, 200, "text/html; charset=utf-8", SHELL);
    }

    private static String tickets() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < TICKETS.size(); i++) {
            if (i > 0) b.append(',');
            b.append(ticket(TICKETS.get(i)));
        }
        return b.append(']').toString();
    }

    private static String ticket(Ticket t) {
        return "{\"id\":" + t.id() + ",\"subject\":\"" + t.subject() + "\",\"requester\":\"" + t.requester() + "\",\"status\":\"" + t.status()
                + "\",\"priority\":\"" + t.priority() + "\",\"updated\":\"" + t.updated() + "\"}";
    }

    /** One page for every address. Everything visible is drawn by the script from JSON. */
    static final String SHELL = """
            <!doctype html><html lang=en><head><meta charset=utf-8><title>Helpdesk</title></head>
            <body><div id=app aria-live=polite>Loading</div>
            <script>
            const app = document.getElementById('app');
            const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
            const nav = '<nav aria-label="Helpdesk"><a href="/desk/">Overview</a> <a href="/desk/tickets">Tickets</a> <a href="/desk/kb">Knowledge base</a> <a href="/desk/new">New request</a></nav>';
            function page(title, body) { document.title = title + ' | Helpdesk'; app.innerHTML = '<header><strong>Helpdesk</strong>' + nav + '</header><main><h1>' + esc(title) + '</h1>' + body + '</main>'; }
            async function get(url) { const r = await fetch(url); return r.json(); }
            async function draw() {
              const p = location.pathname.replace(/\\/$/, '') || '/desk';
              if (p === '/desk') {
                const t = await get('/desk/api/tickets');
                page('Overview', '<section aria-labelledby=q><h2 id=q>Queue</h2><p>' + t.filter(x => x.status === 'Open').length + ' open tickets</p></section>'
                  + '<details><summary>How the queue works</summary><p>Tickets are answered in order of priority.</p></details>');
              } else if (p === '/desk/tickets') {
                const t = await get('/desk/api/tickets');
                const rows = t.map(x => '<div role=row><div role=gridcell><a href="/desk/tickets/' + x.id + '">' + esc(x.subject) + '</a></div><div role=gridcell>' + esc(x.requester)
                  + '</div><div role=gridcell>' + x.status + '</div><div role=gridcell>' + x.priority + '</div></div>').join('');
                page('Tickets', '<button type=button id=open-new>New ticket</button>'
                  + '<div role=grid aria-label="Ticket queue"><div role=row><div role=columnheader aria-sort=none>Subject</div><div role=columnheader aria-sort=none>Requester</div>'
                  + '<div role=columnheader aria-sort=none>Status</div><div role=columnheader aria-sort=descending>Priority</div></div>' + rows + '</div>'
                  + '<dialog id=new-ticket aria-label="New ticket"><h2>New ticket</h2><p>Use the request form.</p><button type=button id=close-new>Close</button></dialog>');
                document.getElementById('open-new').onclick = () => document.getElementById('new-ticket').showModal();
                document.getElementById('close-new').onclick = () => document.getElementById('new-ticket').close();
              } else if (/^\\/desk\\/tickets\\/\\d+$/.test(p)) {
                const r = await fetch('/desk/api' + p.slice(5));
                if (!r.ok) { page('Ticket not found', '<p>There is no such ticket.</p>'); return; }
                const x = await r.json();
                page(x.subject, '<dl><dt>Requester</dt><dd>' + esc(x.requester) + '</dd><dt>Status</dt><dd>' + x.status + '</dd><dt>Priority</dt><dd>' + x.priority + '</dd></dl>'
                  + '<button type=button aria-expanded=false aria-controls=history id=history-toggle>History</button><div id=history hidden><p>Updated ' + x.updated + '</p></div>'
                  + '<p><a href="/desk/tickets">Back to tickets</a></p>');
                const b = document.getElementById('history-toggle');
                b.onclick = () => { const open = b.getAttribute('aria-expanded') !== 'true'; b.setAttribute('aria-expanded', String(open)); document.getElementById('history').hidden = !open; };
              } else if (p === '/desk/kb') {
                const a = await get('/desk/api/articles');
                page('Knowledge base', '<ul>' + a.map(x => '<li><a href="/desk/kb/' + x.slug + '">' + esc(x.title) + '</a></li>').join('') + '</ul>'
                  + '<details><summary>Can I suggest an article?</summary><p>Yes, from the request form.</p></details>');
              } else if (p === '/desk/kb/passwords' || p === '/desk/kb/billing') {
                page(p.endsWith('passwords') ? 'Resetting a password' : 'Changing billing details', '<p>Step-by-step guidance.</p><p><a href="/desk/kb">All articles</a></p>');
              } else if (p === '/desk/new') {
                page('New request', '<form method=post action="/desk/requests" aria-label="Request details">'
                  + '<label for=subject>Subject</label><input id=subject name=subject required minlength=5>'
                  + '<label for=contact>Contact email</label><input id=contact name=contact type=email required>'
                  + '<label for=priority>Priority</label><select id=priority name=priority><option>Low</option><option selected>Normal</option><option>High</option></select>'
                  + '<label for=details>Details</label><textarea id=details name=details></textarea>'
                  + '<button type=submit>Send request</button></form>');
              } else {
                page('Page not found', '<p>Nothing lives here.</p>');
              }
            }
            draw();
            </script></body></html>""";

    private static void json(HttpExchange x, String body) throws IOException {
        send(x, 200, "application/json", body);
    }

    private static void send(HttpExchange x, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }
}
