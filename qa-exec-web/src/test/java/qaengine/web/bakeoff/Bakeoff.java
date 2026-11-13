package qaengine.web.bakeoff;

import com.microsoft.playwright.options.ServiceWorkerPolicy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import qaengine.fixtures.FixtureServer;

/** Driver bake-off (ADR-008). The fixture's request log is the only judge of blocking. */
public final class Bakeoff {

    static final BiPredicate<String, String> POLICY = (method, url) ->
            url.contains("/m/") || url.contains("/ws-") || !(method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS"));

    static final Map<String, String[]> PATHS = new LinkedHashMap<>();
    static {
        p("fetch POST", "/m/fetch-post", "fetch('/m/fetch-post',{method:'POST'}).catch(()=>{})");
        p("XHR DELETE", "/m/xhr-delete", "var x=new XMLHttpRequest();x.open('DELETE','/m/xhr-delete');x.send()");
        p("sendBeacon", "/m/beacon", "navigator.sendBeacon('/m/beacon','x')");
        p("keepalive", "/m/keepalive", "fetch('/m/keepalive',{method:'POST',keepalive:true}).catch(()=>{})");
        p("form POST", "/m/form-post", "document.getElementById('fp').submit()");
        p("iframe POST", "/m/iframe-post", "document.getElementById('f').contentWindow.go()");
        p("web worker", "/m/webworker-post", "new Worker('/worker.js')");
        p("WebSocket", "/ws-attempt", "try{new WebSocket(location.origin.replace('http','ws')+'/ws-attempt')}catch(e){}");
        p("image GET", "/m/img", "new Image().src='/m/img'");
        p("prefetch", "/m/prefetch", "var l=document.createElement('link');l.rel='prefetch';l.href='/m/prefetch';document.head.appendChild(l)");
        p("popup first", "/m/popup-first", "window.open('/m/popup-first')");
    }
    private static void p(String n, String path, String js) { PATHS.put(n, new String[] {path, js}); }

    static <T> String timed(int seconds, Callable<T> c) {
        var ex = Executors.newVirtualThreadPerTaskExecutor();
        try {
            return String.valueOf(ex.submit(c).get(seconds, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            return "STALL";
        } catch (Exception e) {
            return "ERROR " + String.valueOf(e.getCause() == null ? e : e.getCause()).split("\n")[0];
        } finally {
            ex.shutdownNow();
        }
    }

    static void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    static void m1(Supplier<Probe> make, FixtureServer f) {
        try (Probe p = make.get()) {
            p.guard(POLICY);
            String first = timed(15, () -> { p.open(f.url("/bake/paths")); return "ok"; });
            System.out.println("  page load under guard: " + first);
            if (!first.equals("ok")) return;
            for (var e : PATHS.entrySet()) {
                f.clear();
                String r = timed(15, () -> { p.open(f.url("/bake/paths")); pause(150); p.js(e.getValue()[1]); pause(900); return "ok"; });
                System.out.printf("  M1 %-14s %s%s%n", e.getKey(), f.reached(e.getValue()[0]) ? "REACHED SERVER" : "blocked", r.equals("ok") ? "" : " [" + r + "]");
            }
            f.clear();
            String r = timed(15, () -> { p.open(f.url("/bake/paths")); p.click("#redirect"); pause(900); return "ok"; });
            System.out.printf("  M1 %-14s %s [first hop reached=%s]%s%n", "redirect hop", f.reached("/m/redirect-target") ? "REACHED SERVER" : "blocked", f.reached("/redirect-internal"), r.equals("ok") ? "" : " [" + r + "]");
            f.clear();
            r = timed(15, () -> { p.open(f.url("/bake/paths")); p.click("#chain"); pause(900); return "ok"; });
            System.out.printf("  M1 %-14s %s [hop1=%s hop2=%s]%s%n", "2-hop chain", f.reached("/m/redirect-target") ? "REACHED SERVER" : "blocked", f.reached("/redirect-chain"), f.reached("/redirect-internal"), r.equals("ok") ? "" : " [" + r + "]");
            f.clear();
            r = timed(15, () -> { p.open(f.url("/bake/paths")); p.js("document.getElementById('rimg').src='/redirect-internal'"); pause(900); return "ok"; });
            System.out.printf("  M1 %-14s %s%s%n", "subres redirect", f.reached("/m/redirect-target") ? "REACHED SERVER" : "blocked", r.equals("ok") ? "" : " [" + r + "]");
            f.clear();
            r = timed(15, () -> { p.open(f.url("/bake/paths")); p.inNewTab(f.url("/about"), "fetch('/m/newtab-post',{method:'POST'}).catch(()=>{})"); return "ok"; });
            System.out.printf("  M1 %-14s %s%s%n", "new tab POST", f.reached("/m/newtab-post") ? "REACHED SERVER" : "blocked", r.equals("ok") ? "" : " [" + r + "]");
            f.clear();
            r = timed(20, () -> {
                p.open(f.url("/bake/paths"));
                p.js("navigator.serviceWorker.register('/sw.js').catch(()=>{})");
                pause(1200);
                p.open(f.url("/bake/paths"));
                pause(500);
                p.js("fetch('/m/sw-post',{method:'POST'}).catch(()=>{})");
                pause(1000);
                return "ok";
            });
            System.out.printf("  M1 %-14s %s [sw script fetched=%s]%s%n", "service worker", f.reached("/m/sw-post") ? "REACHED SERVER" : "blocked", f.reached("/sw.js"), r.equals("ok") ? "" : " [" + r + "]");
        }
    }

    static void m2(Supplier<Probe> make, FixtureServer f) {
        try (Probe p = make.get()) {
            p.open(f.url("/bake/paths"));
            p.guard((m, u) -> { if (u.contains("/m/")) throw new IllegalStateException("guard failure"); return false; });
            f.clear();
            String r = timed(10, () -> { p.js("fetch('/m/guard-error',{method:'POST'}).catch(()=>{})"); pause(1500); return "ok"; });
            System.out.println("  M2 guard throws: " + (f.reached("/m/guard-error") ? "FAILED OPEN" : "fails closed") + " [" + r + "]");
        } catch (Exception e) { System.out.println("  M2 error " + e.getMessage()); }
    }

    static void m3(Supplier<Probe> make, FixtureServer f) {
        try (Probe p = make.get()) {
            p.guard(POLICY);
            String loaded = timed(15, () -> { p.open(f.url("/about")); return "ok"; });
            if (!loaded.equals("ok")) { System.out.println("  M3 skipped: " + loaded); return; }
            f.clear();
            long t = System.currentTimeMillis();
            String r = timed(60, () -> { p.js("window.__d=0;(async()=>{for(let i=0;i<100;i++){await Promise.all([...Array(10).keys()].map(k=>fetch('/faq?i='+i+'-'+k)));}window.__d=1})()");
                for (int i = 0; i < 550; i++) { pause(100); if (Boolean.TRUE.equals(p.js("return window.__d===1"))) return "ok"; } return "incomplete"; });
            System.out.printf("  M3 1000 allowed requests: %s, reached=%d, %d ms%n", r, f.requests().size(), System.currentTimeMillis() - t);
        }
    }

    static void m4(Supplier<Probe> make, FixtureServer f) {
        try (Probe p = make.get()) {
            for (String path : List.of("/defects/console-error", "/defects/js-error", "/defects/failed-xhr", "/defects/broken-image", "/crash")) {
                p.drainSignals();
                String r = timed(12, () -> { p.open(f.url(path)); pause(500); return "ok"; });
                var s = p.drainSignals().stream().map(x -> x.replace(f.baseUrl(), "")).filter(x -> !x.startsWith("status:200")).toList();
                System.out.printf("  M4 %-24s %s %s%n", path, s, r.equals("ok") ? "" : r);
            }
        }
    }

    static void m5(Supplier<Probe> make, FixtureServer f) {
        try (Probe a = make.get()) {
            a.open(f.url("/about"));
            a.js("document.cookie='leak=1';localStorage.setItem('leak','1')");
        }
        try (Probe b = make.get()) {
            b.open(f.url("/about"));
            System.out.println("  M5 second session sees: cookie=[" + b.js("return document.cookie") + "] storage=[" + b.js("return localStorage.getItem('leak')") + "]");
        }
    }

    static void m7(Supplier<Probe> make, FixtureServer f) {
        try (Probe p = make.get()) {
            p.open(f.url("/traps/forms"));
            p.js("document.querySelector('[name=fullname]').value='AAAA SENSITIVE'");
            byte[] one = p.maskedScreenshot("[name=fullname]");
            p.open(f.url("/traps/forms"));
            p.js("document.querySelector('[name=fullname]').value='ZZZZ DIFFERENT'");
            byte[] two = p.maskedScreenshot("[name=fullname]");
            System.out.println("  M7 masked screenshots identical regardless of value: " + Arrays.equals(one, two));
        }
    }

    static void speed(Supplier<Probe> make, FixtureServer f) {
        try (Probe p = make.get()) {
            p.guard(POLICY);
            if (!timed(15, () -> { p.open(f.url("/about")); return "ok"; }).equals("ok")) { System.out.println("  speed skipped"); return; }
            int fails = 0;
            long t = System.currentTimeMillis();
            for (int i = 0; i < 100; i++) {
                String path = i % 2 == 0 ? "/team" : "/about";
                String r = timed(10, () -> { p.open(f.url(path)); p.click("a"); return "ok"; });
                if (!r.equals("ok")) fails++;
            }
            System.out.printf("  speed 100 page+click cycles: %d ms, failures=%d%n", System.currentTimeMillis() - t, fails);
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, Supplier<Probe>> all = new LinkedHashMap<>();
        all.put("selenium-bidi", SeleniumBidiProbe::new);
        all.put("selenium-cdp", SeleniumCdpProbe::new);
        all.put("playwright", () -> new PlaywrightProbe(ServiceWorkerPolicy.BLOCK));
        try (FixtureServer f = FixtureServer.start()) {
            for (var e : all.entrySet()) {
                if (args.length > 0 && !Arrays.asList(args).contains(e.getKey())) continue;
                System.out.println("=== " + e.getKey());
                for (var s : List.<java.util.function.BiConsumer<Supplier<Probe>, FixtureServer>>of(Bakeoff::m1, Bakeoff::m2, Bakeoff::m3, Bakeoff::m4, Bakeoff::m5, Bakeoff::m7, Bakeoff::speed)) {
                    try { s.accept(e.getValue(), f); } catch (Throwable t) { System.out.println("  scenario error: " + String.valueOf(t).split("\n")[0]); }
                }
            }
        }
        System.exit(0);
    }
}
