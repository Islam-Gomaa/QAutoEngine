package qaengine.fixtures;

import java.util.LinkedHashMap;
import java.util.Map;

/** Static fixture content. Wording avoids words the legacy engine treated as terminal. */
final class Pages {

    static final Map<String, String> ALL = new LinkedHashMap<>();

    private static void page(String path, String title, String body) {
        ALL.put(path, "<!doctype html><html lang=en><head><meta charset=utf-8><title>" + title
                + "</title></head><body><h1>" + title + "</h1>" + body + "</body></html>");
    }

    static {
        page("/", "Fixture Home", """
            <nav><a href="/about">About</a> <a href="/team">Team</a> <a href="/docs">Docs</a> <a href="/faq">FAQ</a>
            <a href="/defects/links">Links</a> <a href="/defects/js-error">Script</a> <a href="/defects/console-error">Console</a>
            <a href="/defects/failed-xhr">Data</a> <a href="/defects/broken-image">Gallery</a>
            <a href="/traps/danger">Account</a> <a href="/traps/get">Items</a> <a href="/traps/forms">Forms</a>
            <a href="/traps/external">Partners</a> <a href="/modern/spa">Spa</a> <a href="/modern/frames">Frames</a>
            <a href="/modern/shadow">Shadow</a> <a href="/modern/tabs">Tabs</a> <a href="/calendar?month=1">Calendar</a>
            <a href="/hostile">Hostile</a></nav>""");
        page("/about", "About", "<p>About us.</p><a href=\"/\">Home</a> <a href=\"/team\">Team</a>");
        page("/team", "Team", "<p>The team.</p><a href=\"/about\">About</a>");
        page("/docs", "Docs", "<p>Docs.</p><a href=\"/faq\">FAQ</a>");
        page("/faq", "FAQ", "<p>Questions.</p><a href=\"/docs\">Docs</a>");

        page("/defects/links", "Links", "<a href=\"/missing-page\">Missing</a> <a href=\"/crash\">Crash</a> <a href=\"/\">Home</a>");
        page("/defects/js-error", "Script", "<a href=\"/\">Home</a><script>plantedUndefinedFunction();</script>");
        page("/defects/console-error", "Console", "<a href=\"/\">Home</a><script>console.error('planted console error');</script>");
        page("/defects/failed-xhr", "Data", "<a href=\"/\">Home</a><script>fetch('/api/fail').catch(()=>{});</script>");
        page("/defects/broken-image", "Gallery", "<a href=\"/\">Home</a><img src=\"/missing-image.png\" alt=gallery>");

        page("/traps/danger", "Account", """
            <a href="/">Home</a>
            <button aria-label="Delete account" onclick="fetch('/m/delete-account',{method:'POST'})"><svg width=16 height=16><rect width=16 height=16 /></svg></button>
            <button onclick="fetch('/m/confirm-payment',{method:'POST'})">Confirm payment</button>
            <button onclick="fetch('/m/remove-user',{method:'POST'})">Remove user</button>""");
        page("/traps/get", "Items", """
            <a href="/">Home</a> <a href="/items/5/delete">Discard</a> <a href="/items?action=remove&id=5">Clear</a>
            <a href="/logout">Exit</a> <a href="/items/9" data-method="delete">Drop</a>
            <a href="/items/7/reset" onclick="return confirm('Sure?')">Restart</a>
            <a href="/verify/9f8e7d6c5b4a39281706f5e4d3c2b1a0">Verify</a>
            <a href="/download/report.bin" download>Report</a> <a href="/tools/nightly">Nightly</a>""");
        page("/traps/forms", "Forms", """
            <a href="/">Home</a>
            <form id=contact action="/submitted" method=post><input name=fullname required><input name=email type=email required>
            <input name=secret type=password><button type=submit class="btn btn-primary">Send</button></form>
            <form id=override action="/m/override" method=post><input type=hidden name=_method value=delete><button>Go</button></form>
            <script>window.gql=(q)=>fetch('/graphql',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({query:q})})</script>""");
        page("/traps/external", "Partners", """
            <a href="/">Home</a> <a href="http://blocked.invalid/">Partner</a> <a href="/redirect-external">Offer</a>""");

        page("/modern/spa", "Spa", """
            <a href="/">Home</a> <a href="#/one" id=one>One</a> <a href="#/two" id=two>Two</a><div id=view>start</div>
            <script>addEventListener('hashchange',()=>{document.getElementById('view').innerHTML=
            location.hash==='#/two'?'<a href="/spa-deep">Deep</a>':'<p>one view</p>'})</script>""");
        page("/spa-deep", "Spa Deep", "<a href=\"/\">Home</a>");
        page("/modern/frames", "Frames", "<a href=\"/\">Home</a><iframe id=inner src=\"/modern/frame-inner\"></iframe>");
        page("/modern/frame-inner", "Inner", "<a href=\"/framed-deep\" target=_top>Deep</a>");
        page("/framed-deep", "Framed Deep", "<a href=\"/\">Home</a>");
        page("/modern/shadow", "Shadow", """
            <a href="/">Home</a><div id=host></div><script>host.attachShadow({mode:'open'}).innerHTML='<a href="/shadow-deep">Deep</a>'</script>""");
        page("/shadow-deep", "Shadow Deep", "<a href=\"/\">Home</a>");
        page("/modern/tabs", "Tabs", "<a href=\"/\">Home</a> <a href=\"/tab-deep\" target=_blank>Open</a>");
        page("/tab-deep", "Tab Deep", "<a href=\"/\">Home</a>");
        ALL.put("/hostile", "<!doctype html><html><head><title>&lt;img src=x onerror=window.__xss=1&gt;</title></head><body>"
                + "<a href=\"/hostile-next\" title=\"&lt;script&gt;window.__xss=1&lt;/script&gt;\">&lt;img src=x onerror=window.__xss=1&gt;</a>"
                + "<script>console.error('<img src=x onerror=window.__xss=1>');</script></body></html>");
        page("/hostile-next", "Hostile Next", "<a href=\"/\">Home</a>");
        page("/items", "Item List", "<a href=\"/\">Home</a>");
        page("/submitted", "Received", "<a href=\"/\">Home</a>");

        page("/login", "Login", """
            <form action="/session" method=post><input name=username autocomplete=username>
            <input name=password type=password><button type=submit>Sign in</button></form>""");

        page("/bake/paths", "Paths", """
            <iframe id=f src="/bake/frame"></iframe><form id=fp method=post action="/m/form-post" target=f><input name=a value=1></form>
            <a id=redirect href="/redirect-internal">r</a> <a id=chain href="/redirect-chain">c</a>
            <img id=rimg alt=x>""");
        page("/bake/frame", "Frame", "<script>window.go=()=>fetch('/m/iframe-post',{method:'POST'}).catch(()=>{})</script>");
    }

    private Pages() {}
}
