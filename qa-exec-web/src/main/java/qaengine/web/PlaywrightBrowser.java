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
import qaengine.core.model.Control;
import qaengine.core.model.Navigation;
import qaengine.core.model.PageSnapshot;
import qaengine.core.model.Signal;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.Decision;
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
                redirects.put(r.url(), URI.create(r.url()).resolve(location).toString());
                route.abort("blockedbyclient");
                return;
            }
            route.fulfill(new Route.FulfillOptions().setResponse(response));
        } catch (PlaywrightException e) {
            // The page or request went away, or the fetch failed. Never let the request continue.
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
        newPage();
        try {
            Response response = page.navigate(url, new Page.NavigateOptions().setTimeout(NAV_TIMEOUT_MS));
            settle();
            return Navigation.loaded(response == null ? 0 : response.status(), page.url());
        } catch (PlaywrightException e) {
            String target = redirects.remove(url);
            if (target != null) return Navigation.redirected(url, target);
            boolean blocked = signals.stream().anyMatch(s -> s.type() == Signal.Type.BLOCKED_BY_POLICY && s.url().equals(url));
            return blocked ? Navigation.blocked(url, "blocked by policy") : Navigation.failed(url, firstLine(e.getMessage()));
        }
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
