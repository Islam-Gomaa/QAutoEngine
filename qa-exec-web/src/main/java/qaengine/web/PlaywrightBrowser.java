package qaengine.web;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.model.Control;
import qaengine.core.model.Navigation;
import qaengine.core.model.PageSnapshot;
import qaengine.core.model.Signal;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.Decision;
import qaengine.core.policy.ExecutionLock;
import qaengine.core.policy.HttpRequestInfo;
import qaengine.core.port.BrowserPort;

/**
 * Browser adapter that enforces the gate at the request layer. Service workers are blocked because
 * routes cannot see their traffic, and redirects are never followed because routes do not run on
 * redirect hops. Not thread safe: one instance per job, used from one thread.
 */
public final class PlaywrightBrowser implements BrowserPort {

    private static final int NAV_TIMEOUT_MS = 15_000;
    private static final String DOM_QUIET = """
        () => new Promise(resolve => {
          let timer = setTimeout(done, 150);
          const cap = setTimeout(done, 1500);
          const observer = new MutationObserver(() => { clearTimeout(timer); timer = setTimeout(done, 150); });
          function done() { observer.disconnect(); clearTimeout(cap); clearTimeout(timer); resolve(true); }
          observer.observe(document, {subtree: true, childList: true, attributes: true, characterData: true});
        })
        """;

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private Page page;
    private boolean openingPage;
    private final ActionGate gate;
    private final List<Signal> signals = new CopyOnWriteArrayList<>();
    private final Map<String, String> redirects = new HashMap<>();
    /** Where the last page-level redirect pointed. A form post's URL is not known in advance, so this is how its redirect is found. */
    private volatile String lastDocumentRedirect;
    /**
     * Why the guard's own fetch failed, by URL. The guard then aborts the request, so without this the
     * browser would report every DNS, connection or TLS failure as a refusal by the client.
     */
    private final Map<String, String> fetchErrors = new java.util.concurrent.ConcurrentHashMap<>();

    public PlaywrightBrowser(ActionGate gate) {
        this(gate, new BrowserType.LaunchOptions().setHeadless(true));
    }

    public PlaywrightBrowser(ActionGate gate, BrowserType.LaunchOptions launch) {
        ExecutionLock.requireAllowed(gate.policy().scope());
        this.gate = gate;
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(launch);
        this.context = browser.newContext(new Browser.NewContextOptions()
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK)
                .setAcceptDownloads(false));
        context.setDefaultTimeout(NAV_TIMEOUT_MS);
        context.route(Pattern.compile(".*"), this::guard);
        context.routeWebSocket(Pattern.compile(".*"), ws -> {
            Decision d = gate.decideRequest(HttpRequestInfo.of("GET", ws.url(), HttpRequestInfo.Resource.WEBSOCKET));
            if (d.allowed()) {
                ws.connectToServer();
            } else {
                signals.add(Signal.of(Signal.Type.BLOCKED_BY_POLICY, ws.url(), d.reason().name()));
                ws.close();
            }
        });
        context.onPage(popup -> {
            if (!openingPage) popup.close();
        });
        newPage();
    }

    /**
     * Each navigation gets a fresh page in the same context. Cookies persist, but aborted navigations
     * cannot race with the next one, and timers from the last page cannot keep running.
     */
    private void newPage() {
        if (page != null) page.close();
        openingPage = true;
        Page fresh = context.newPage();
        openingPage = false;
        fresh.onDialog(dialog -> {
            signals.add(Signal.of(Signal.Type.DIALOG_DISMISSED, fresh.url(), dialog.type()));
            dialog.dismiss();
        });
        fresh.onConsoleMessage(m -> {
            if ("error".equals(m.type()) && !m.text().startsWith("Failed to load resource")) {
                signals.add(Signal.of(Signal.Type.CONSOLE_ERROR, fresh.url(), m.text()));
            }
        });
        fresh.onPageError(e -> signals.add(Signal.of(Signal.Type.PAGE_EXCEPTION, fresh.url(), e)));
        fresh.onResponse(this::onResponse);
        page = fresh;
    }

    private void onResponse(Response r) {
        Request q = r.request();
        signals.add(Signal.response(r.url(), r.status(), q.resourceType()));
    }

    private void guard(Route route) {
        Request r = route.request();
        try {
            Decision d = gate.decideRequest(new HttpRequestInfo(r.method(), r.url(), r.headers(), r.postData(), resource(r)));
            if (!d.allowed()) {
                signals.add(Signal.of(Signal.Type.BLOCKED_BY_POLICY, r.url(), d.reason() + ": " + d.detail()));
                route.abort("blockedbyclient");
                return;
            }
            var response = route.fetch(new Route.FetchOptions().setMaxRedirects(0));
            String location = response.headers().get("location");
            if (response.status() >= 300 && response.status() < 400 && location != null) {
                String target = URI.create(r.url()).resolve(location).toString();
                redirects.put(r.url(), target);
                if ("document".equals(r.resourceType())) lastDocumentRedirect = target;
                route.abort("blockedbyclient");
                return;
            }
            route.fulfill(new Route.FulfillOptions().setResponse(response));
        } catch (PlaywrightException e) {
            // The page or request went away, or the fetch failed. Never let the request continue.
            fetchErrors.put(r.url(), networkCode(e.getMessage()));
            signals.add(Signal.of(Signal.Type.REQUEST_FAILED, r.url(), "request could not be completed"));
            abortQuietly(route);
        } catch (RuntimeException e) {
            signals.add(Signal.of(Signal.Type.BLOCKED_BY_POLICY, r.url(), "guard error"));
            abortQuietly(route);
        }
    }

    private static void abortQuietly(Route route) {
        try {
            route.abort("blockedbyclient");
        } catch (PlaywrightException ignored) {
            // Already handled or gone; either way the request did not proceed.
        }
    }

    private static HttpRequestInfo.Resource resource(Request r) {
        return switch (r.resourceType()) {
            case "document" -> HttpRequestInfo.Resource.DOCUMENT;
            case "fetch", "xhr", "ping", "eventsource" -> HttpRequestInfo.Resource.FETCH;
            case "script", "stylesheet", "image", "font", "media", "manifest" -> HttpRequestInfo.Resource.STATIC;
            default -> HttpRequestInfo.Resource.OTHER;
        };
    }

    @Override
    public Navigation open(String url) {
        redirects.remove(url);
        fetchErrors.remove(url);
        newPage();
        try {
            Response response = page.navigate(url, new Page.NavigateOptions().setTimeout(NAV_TIMEOUT_MS));
            settle();
            return Navigation.loaded(response == null ? 0 : response.status(), page.url());
        } catch (PlaywrightException e) {
            String target = redirects.remove(url);
            if (target != null) return Navigation.redirected(url, target);
            String cause = fetchErrors.remove(url);
            if (cause != null) return Navigation.failed(url, cause);
            boolean blocked = signals.stream().anyMatch(s -> s.type() == Signal.Type.BLOCKED_BY_POLICY && s.url().equals(url));
            return blocked ? Navigation.blocked(url, "blocked by policy") : Navigation.failed(url, firstLine(e.getMessage()));
        }
    }

    /** The fetch reports Node-style errors; this names them the way the browser would, and keeps nothing else from the message. */
    static String networkCode(String message) {
        String m = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("enotfound") || m.contains("eai_again") || m.contains("getaddrinfo")) return "net::ERR_NAME_NOT_RESOLVED";
        if (m.contains("econnrefused")) return "net::ERR_CONNECTION_REFUSED";
        if (m.contains("tunnel") || m.contains("proxy")) return "net::ERR_TUNNEL_CONNECTION_FAILED";
        if (m.contains("eproto") || m.contains("ssl") || m.contains("tls") || m.contains("certificate") || m.contains("cert_") || m.contains("self signed")
                || m.contains("self-signed") || m.contains("wrong version number")) return "net::ERR_SSL_PROTOCOL_ERROR";
        if (m.contains("econnreset") || m.contains("socket hang up")) return "net::ERR_CONNECTION_RESET";
        if (m.contains("etimedout") || m.contains("timeout")) return "net::ERR_CONNECTION_TIMED_OUT";
        return "net::ERR_FAILED";
    }

    /** Lets late scripts and subresources report in, without treating busy pages as failures. */
    private void settle() {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(3000));
        } catch (PlaywrightException ignored) {
            // Polling or streaming pages never go idle. That is not an error.
        }
    }

    /** Client-side rendering finishes after the network does, so wait for the DOM to stop changing. */
    private void waitForDomQuiet() {
        page.evaluate(DOM_QUIET);
    }

    @Override
    public PageSnapshot snapshot() {
        return DomReader.read(page);
    }

    @Override
    public void activate(Control control) {
        for (String locator : control.locators()) {
            var candidate = page.locator(locator);
            if (candidate.count() > 0) {
                candidate.first().click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(3000));
                settle();
                waitForDomQuiet();
                return;
            }
        }
        throw new IllegalStateException("No locator matched for: " + control.label());
    }

    @Override
    public Navigation submitLogin(String username, String password, String oneTimeCode) {
        var secret = page.locator("input[type=password]").first();
        if (secret.count() == 0) return Navigation.failed(page.url(), "no password field");
        var form = secret.locator("xpath=ancestor::form[1]");
        var user = form.locator("input:not([type=password]):not([type=hidden]):not([type=submit])").first();
        if (user.count() > 0) user.fill(username);
        secret.fill(password);
        if (oneTimeCode != null) {
            var code = form.locator("input[autocomplete=one-time-code], input[name=code], input[name=otp], input[name=totp]").first();
            if (code.count() == 0) return Navigation.failed(page.url(), "no one-time code field");
            code.fill(oneTimeCode);
        }
        String submitUrl = URI.create(page.url()).resolve(String.valueOf(form.getAttribute("action"))).toString();
        redirects.remove(submitUrl);
        try {
            Response response = page.waitForNavigation(new Page.WaitForNavigationOptions().setTimeout(NAV_TIMEOUT_MS),
                    () -> form.locator("button[type=submit], input[type=submit], button").first().click());
            return Navigation.loaded(response == null ? 0 : response.status(), page.url());
        } catch (PlaywrightException e) {
            String target = redirects.remove(submitUrl);
            return target != null ? Navigation.redirected(submitUrl, target) : Navigation.failed(submitUrl, "login did not complete");
        }
    }

    private com.microsoft.playwright.Locator resolve(LocatorSpec s) {
        return allOf(s).first();
    }

    @Override
    public String currentUrl() {
        return page.url();
    }

    @Override
    public boolean isVisible(LocatorSpec element) {
        try {
            return resolve(element).isVisible();
        } catch (PlaywrightException e) {
            return false; // An unusable selector finds nothing.
        }
    }

    @Override
    public boolean appears(LocatorSpec element, java.time.Duration timeout) {
        try {
            resolve(element).waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE).setTimeout(timeout.toMillis()));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    @Override
    public void fill(LocatorSpec field, String value) {
        resolve(field).fill(value == null ? "" : value, new com.microsoft.playwright.Locator.FillOptions().setTimeout(5000));
    }

    @Override
    public boolean fillCode(LocatorSpec field, String code) {
        var all = allOf(field);
        int boxes = all.count();
        if (boxes <= 1) {
            fill(field, code);
            return true;
        }
        if (boxes != code.length()) return false;
        for (int i = 0; i < boxes; i++) all.nth(i).fill(String.valueOf(code.charAt(i)), new com.microsoft.playwright.Locator.FillOptions().setTimeout(5000));
        return true;
    }

    /** Every match, not just the first: segmented code inputs share one locator. */
    private com.microsoft.playwright.Locator allOf(LocatorSpec s) {
        return switch (s.strategy()) {
            case ROLE -> page.getByRole(com.microsoft.playwright.options.AriaRole.valueOf(s.value().toUpperCase(java.util.Locale.ROOT)),
                    s.name() == null ? new Page.GetByRoleOptions() : new Page.GetByRoleOptions().setName(s.name()));
            case LABEL -> page.getByLabel(s.value());
            case PLACEHOLDER -> page.getByPlaceholder(s.value());
            case TEST_ID -> page.getByTestId(s.value());
            case CSS -> page.locator(s.value());
        };
    }

    @Override
    public Navigation submitWith(LocatorSpec button, java.time.Duration timeout) {
        lastDocumentRedirect = null;
        int before = signals.size();
        var target = resolve(button);
        String from = page.url();
        try {
            Response response = page.waitForNavigation(new Page.WaitForNavigationOptions().setTimeout(timeout.toMillis()),
                    () -> target.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(5000)));
            settle();
            return Navigation.loaded(response == null ? 0 : response.status(), page.url());
        } catch (com.microsoft.playwright.TimeoutError e) {
            // No navigation: the page signed in, or showed its error, in place.
            String redirect = lastDocumentRedirect;
            if (redirect != null) return Navigation.redirected(from, redirect);
            settle();
            waitForDomQuiet();
            return Navigation.loaded(0, page.url());
        } catch (PlaywrightException e) {
            String redirect = lastDocumentRedirect;
            if (redirect != null) return Navigation.redirected(from, redirect);
            boolean blocked = signals.stream().skip(Math.min(before, signals.size())).anyMatch(x -> x.type() == Signal.Type.BLOCKED_BY_POLICY);
            return blocked ? Navigation.blocked(from, "sign-in request refused") : Navigation.failed(from, firstLine(e.getMessage()));
        }
    }

    private static final String SUGGEST = """
        () => {
          const visible = el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
          const pw = [...document.querySelectorAll('input[type=password]')].find(visible);
          if (!pw) return null;
          const form = pw.closest('form') || document;
          const skip = ['password', 'hidden', 'submit', 'button', 'checkbox', 'radio', 'image', 'reset', 'file'];
          const user = [...form.querySelectorAll('input')].find(e => e !== pw && visible(e) && !skip.includes((e.getAttribute('type') || 'text').toLowerCase()));
          const submit = [...form.querySelectorAll('button[type=submit], input[type=submit], button:not([type])')].find(visible);
          const d = el => el && ({
            tag: el.tagName.toLowerCase(), testid: el.getAttribute('data-testid'), id: el.id || null, name: el.getAttribute('name'),
            label: (el.labels && el.labels[0] ? el.labels[0].innerText : el.getAttribute('aria-label') || '').trim() || null,
            placeholder: el.getAttribute('placeholder'), text: (el.innerText || el.value || '').trim() || null });
          return { username: d(user), password: d(pw), submit: d(submit) };
        }
        """;

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, LocatorSpec> suggestLoginFields() {
        Map<String, Object> found;
        try {
            found = (Map<String, Object>) page.evaluate(SUGGEST);
        } catch (PlaywrightException e) {
            return Map.of();
        }
        if (found == null) return Map.of();
        Map<String, LocatorSpec> out = new java.util.LinkedHashMap<>();
        for (String field : List.of("username", "password", "submit")) {
            Object raw = found.get(field);
            if (raw instanceof Map<?, ?> m) {
                LocatorSpec spec = suggestion((Map<String, Object>) m, field.equals("submit"));
                if (spec != null) out.put(field, spec);
            }
        }
        return out;
    }

    /** The most stable way to find this element: test id, then label or button text, then placeholder, then CSS. */
    private static LocatorSpec suggestion(Map<String, Object> el, boolean button) {
        java.util.function.Function<String, String> get = k -> el.get(k) instanceof String v && !v.isBlank() ? v : null;
        try {
            if (get.apply("testid") != null) return LocatorSpec.of(LocatorSpec.Strategy.TEST_ID, get.apply("testid"));
            if (button && get.apply("text") != null) return new LocatorSpec(LocatorSpec.Strategy.ROLE, "button", get.apply("text"));
            if (!button && get.apply("label") != null) return LocatorSpec.of(LocatorSpec.Strategy.LABEL, get.apply("label"));
            if (get.apply("placeholder") != null) return LocatorSpec.of(LocatorSpec.Strategy.PLACEHOLDER, get.apply("placeholder"));
            if (get.apply("id") != null && get.apply("id").matches("[A-Za-z][A-Za-z0-9_-]*")) return LocatorSpec.of(LocatorSpec.Strategy.CSS, "#" + get.apply("id"));
            if (get.apply("name") != null && get.apply("name").matches("[A-Za-z0-9_.-]+")) return LocatorSpec.of(LocatorSpec.Strategy.CSS, get.apply("tag") + "[name=\"" + get.apply("name") + "\"]");
        } catch (RuntimeException e) {
            return null; // Too long or otherwise unusable: no suggestion beats a wrong one.
        }
        return null;
    }

    @Override
    public qaengine.core.discovery.PageInventory describePage() {
        return DiscoveryReader.read(page);
    }

    @Override
    public byte[] screenshot(List<String> maskSelectors, List<LocatorSpec> maskElements) {
        List<com.microsoft.playwright.Locator> mask = new ArrayList<>(maskSelectors.stream().map(page::locator).toList());
        for (LocatorSpec element : maskElements) mask.add(resolve(element));
        return page.screenshot(new Page.ScreenshotOptions().setMask(mask).setFullPage(true));
    }

    @Override
    public void importCookies(String name, String value, String url) {
        context.addCookies(List.of(new com.microsoft.playwright.options.Cookie(name, value).setUrl(url).setHttpOnly(true)));
    }

    @Override
    public List<Signal> drainSignals() {
        page.waitForTimeout(20);
        List<Signal> copy = new ArrayList<>(signals);
        signals.clear();
        return copy;
    }

    @Override
    public byte[] screenshot(List<String> maskSelectors) {
        var mask = maskSelectors.stream().map(page::locator).toList();
        return page.screenshot(new Page.ScreenshotOptions().setMask(mask).setFullPage(true));
    }

    @Override
    public String domSnapshot() {
        return page.content();
    }

    /** Test seam: run a script as the page itself would. */
    public Object evaluate(String script) {
        return page.evaluate("() => { " + script + " }");
    }

    public void pause(int ms) {
        page.waitForTimeout(ms);
    }

    /** Playwright errors are multi-line; keep the one line that names the problem. */
    private static String firstLine(String s) {
        if (s == null) return "";
        return s.lines().filter(l -> l.contains("message=") || l.contains("net::")).findFirst()
                .orElse(s.lines().findFirst().orElse("")).trim();
    }

    @Override
    public void close() {
        try {
            context.close();
            browser.close();
        } finally {
            playwright.close();
        }
    }
}
