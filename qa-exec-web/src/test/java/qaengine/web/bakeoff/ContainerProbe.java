package qaengine.web.bakeoff;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import java.util.List;
import qaengine.fixtures.FixtureServer;

/** Gate M6: proves the guard and the Chromium sandbox work inside the hardened container. */
public final class ContainerProbe {
    /** chrome://sandbox is not available here, so the renderer is inspected directly. */
    private static void inspectRenderers() throws Exception {
        String selfPidNs = java.nio.file.Files.readSymbolicLink(java.nio.file.Path.of("/proc/self/ns/pid")).toString();
        try (var procs = java.nio.file.Files.list(java.nio.file.Path.of("/proc"))) {
            for (var dir : procs.filter(d -> d.getFileName().toString().matches("\\d+")).toList()) {
                String cmd;
                try { cmd = new String(java.nio.file.Files.readAllBytes(dir.resolve("cmdline"))).replace('\0', ' '); } catch (Exception e) { continue; }
                if (!cmd.contains("--type=renderer")) continue;
                var status = java.nio.file.Files.readAllLines(dir.resolve("status")).stream()
                        .filter(l -> l.startsWith("Seccomp:") || l.startsWith("NoNewPrivs") || l.startsWith("CapEff")).map(l -> l.replace("\t", " ")).toList();
                String ns;
                try { ns = java.nio.file.Files.readSymbolicLink(dir.resolve("ns/pid")).toString(); } catch (Exception e) { ns = "unreadable (" + e.getClass().getSimpleName() + ")"; }
                System.out.println("renderer no-sandbox-flag=" + cmd.contains("--no-sandbox") + " " + status + " pidns-differs=" + !ns.equals(selfPidNs) + " " + ns);
            }
        }
    }

    public static void main(String[] a) throws Exception {
        System.out.println("uid=" + new com.sun.security.auth.module.UnixSystem().getUid() + " java=" + Runtime.version().feature());
        try (FixtureServer f = FixtureServer.start(); Playwright pw = Playwright.create()) {
            var opts = new BrowserType.LaunchOptions().setHeadless(true).setChromiumSandbox(true).setArgs(List.of("--headless=new"));
            if (a.length > 0) opts.setChannel(a[0]);
            try (Browser b = pw.chromium().launch(opts)) {
                BrowserContext c = b.newContext();
                c.route("**/m/**", r -> r.abort("blockedbyclient"));
                var p = c.newPage();
                p.navigate(f.url("/about"));
                System.out.println("title=" + p.title());
                p.evaluate("() => { fetch('/m/in-container',{method:'POST'}).catch(()=>{}) }");
                p.waitForTimeout(500);
                System.out.println("trap reached=" + f.reached("/m/in-container"));
                inspectRenderers();
                System.out.println("flags: " + new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/proc/self/status"))).lines().filter(l -> l.startsWith("CapEff") || l.startsWith("NoNewPrivs") || l.startsWith("Seccomp:")).toList());
            }
        }
    }
}
