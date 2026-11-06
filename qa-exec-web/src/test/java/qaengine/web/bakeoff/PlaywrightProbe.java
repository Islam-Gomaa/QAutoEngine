package qaengine.web.bakeoff;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

final class PlaywrightProbe implements Probe {
    private final Playwright pw = Playwright.create();
    private final Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    private final BrowserContext context;
    private final Page page;
    private final List<String> signals = new CopyOnWriteArrayList<>();
    final List<String> redirects = new CopyOnWriteArrayList<>();

    PlaywrightProbe(ServiceWorkerPolicy serviceWorkers) {
        context = browser.newContext(new Browser.NewContextOptions().setServiceWorkers(serviceWorkers));
        context.setDefaultTimeout(8000);
        page = context.newPage();
        context.onConsoleMessage(m -> signals.add("console:" + m.text()));
        context.onWebError(e -> signals.add("exception:" + e.error()));
        context.onResponse(r -> signals.add("status:" + r.status() + " " + r.url()));
    }

    @Override public String name() { return "playwright"; }

    @Override public void guard(BiPredicate<String, String> block) {
        context.route(Pattern.compile(".*"), route -> {
            var r = route.request();
            if (block.test(r.method(), r.url())) { route.abort("blockedbyclient"); return; }
            // Playwright does not re-run routes on redirect hops, so the browser must never follow one.
            // Fetch without following, and hand any redirect target back to the engine as a new candidate.
            var response = route.fetch(new com.microsoft.playwright.Route.FetchOptions().setMaxRedirects(0));
            if (response.status() >= 300 && response.status() < 400 && response.headers().containsKey("location")) {
                redirects.add(response.headers().get("location"));
                route.abort("blockedbyclient");
                return;
            }
            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setResponse(response));
        });
        context.routeWebSocket(Pattern.compile(".*"), ws -> {
            if (block.test("GET", ws.url())) ws.close(); else ws.connectToServer();
        });
    }

    @Override public void open(String url) { page.navigate(url); }
    @Override public Object js(String s) { return page.evaluate("() => { " + s + " }"); }
    @Override public void click(String css) { page.locator(css).first().click(); }

    @Override public void inNewTab(String url, String script) {
        Page p = context.newPage();
        p.navigate(url);
        p.evaluate("() => { " + script + " }");
        p.waitForTimeout(800);
        p.close();
    }

    @Override public List<String> drainSignals() { page.waitForTimeout(50); var c = new ArrayList<>(signals); signals.clear(); return c; }

    @Override public byte[] maskedScreenshot(String css) {
        return page.screenshot(new Page.ScreenshotOptions().setMask(List.of(page.locator(css))));
    }

    @Override public void close() { context.close(); browser.close(); pw.close(); }
}
