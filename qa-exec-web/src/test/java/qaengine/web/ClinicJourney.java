package qaengine.web;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The Dashboard, in a real browser, against the running stack after `scripts/onboard.py scripts/manifests/clinic.json` registered the clinic:
 * the real target's project offers nothing that can run, the clinic's fixture environment and its suite can be
 * chosen, and a run started from the page reaches its end with scripted steps, verdicts and screenshots.
 *
 * <pre>java -cp "$(cat cp.txt):qa-exec-web/target/test-classes:qa-exec-web/target/classes" qaengine.web.ClinicJourney</pre>
 */
public final class ClinicJourney {

    private static final String BASE = System.getenv().getOrDefault("QA_BASE", "https://localhost:8443");
    private static final Path SHOTS = Path.of(System.getenv().getOrDefault("QA_SHOTS", "docs/screenshots"));
    private static final List<String> failures = new ArrayList<>();

    private ClinicJourney() {}

    private static void check(String name, boolean ok) {
        if (!ok) failures.add(name);
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
    }

    private static Page.GetByRoleOptions named(String name) {
        return new Page.GetByRoleOptions().setName(name).setExact(true);
    }

    private static void shot(Page page, String name) {
        page.screenshot(new Page.ScreenshotOptions().setPath(SHOTS.resolve(name + ".png")).setFullPage(true));
    }

    private static void project(Page page, String name) {
        page.getByTestId("project-selector").locator("xpath=..").click();
        page.getByRole(AriaRole.OPTION, named(name)).click();
        page.waitForTimeout(500);
    }

    private static void execution(Page page) {
        page.getByRole(AriaRole.NAVIGATION, new Page.GetByRoleOptions().setName("Main")).getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Test Execution")).click();
        page.getByRole(AriaRole.HEADING, named("Test Execution")).waitFor();
    }

    public static void main(String[] args) throws Exception {
        String password = Files.readString(Path.of(System.getenv().getOrDefault("QA_ADMIN_PASSWORD_FILE", "deploy/secrets/admin_password"))).trim();
        Files.createDirectories(SHOTS);
        try (Playwright pw = Playwright.create(); Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setViewportSize(1440, 900)).newPage();
            page.setDefaultTimeout(20000);
            List<String> serverErrors = new ArrayList<>();
            page.onResponse(r -> { if (r.status() >= 500) serverErrors.add(r.status() + " " + r.url()); });
            try {
                walk(page, password);
            } catch (com.microsoft.playwright.PlaywrightException e) {
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(System.getProperty("java.io.tmpdir"), "clinic-journey-failure.png")).setFullPage(true));
                check("journey stopped at " + page.url() + ": " + e.getMessage().lines().filter(l -> l.contains("waiting for") || l.contains("Error")).limit(3).toList(), false);
            }
            check("no server errors " + serverErrors, serverErrors.isEmpty());
        }
        System.out.println(failures.isEmpty() ? "clinic journey passed" : "clinic journey FAILED: " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static void walk(Page page, String password) {
        page.navigate(BASE);
        page.getByLabel("Email").fill("admin@qa.local");
        page.getByLabel("Password").fill(password);
        page.getByRole(AriaRole.BUTTON, named("Sign in")).click();
        page.getByRole(AriaRole.HEADING, named("Dashboard")).waitFor();
        check("signed in", true);

        // The real target's project: listed, locked, and nothing can be started.
        project(page, "Jeddah LPR");
        execution(page);
        page.getByTestId("no-runnable-environment").waitFor();
        check("Jeddah LPR says why nothing can run", page.getByTestId("no-runnable-environment").innerText().contains("Host not approved"));
        check("Start fixture run is disabled for Jeddah LPR", page.getByRole(AriaRole.BUTTON, named("Start fixture run")).isDisabled());
        page.getByLabel("Environment", new Page.GetByLabelOptions().setExact(true)).click();
        Locator lpr = page.getByRole(AriaRole.OPTION, named("LPR system / Test (Web), locked: host not approved"));
        check("the LPR environment is listed, locked", lpr.count() == 1 && "true".equals(lpr.getAttribute("aria-disabled")));
        shot(page, "clinic-01-lpr-locked");
        page.keyboard().press("Escape");

        // The clinic's project: its fixture environment and suite can be chosen, and the run can start.
        project(page, "Clinic records (fixture)");
        execution(page);
        check("no lock message where a fixture can run", page.getByTestId("no-runnable-environment").count() == 0);
        check("Start fixture run is disabled until an environment is chosen", page.getByRole(AriaRole.BUTTON, named("Start fixture run")).isDisabled());
        page.getByLabel("Environment", new Page.GetByLabelOptions().setExact(true)).click();
        Locator clinic = page.getByRole(AriaRole.OPTION, named("Clinic records / Local fixture (Web)"));
        check("the clinic fixture environment is offered and selectable", clinic.count() == 1 && !"true".equals(clinic.getAttribute("aria-disabled")));
        clinic.click();
        page.getByLabel("Scope", new Page.GetByLabelOptions().setExact(true)).click();
        Locator suite = page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName(Pattern.compile("^Clinic smoke \\(\\d+ automated\\)$")));
        check("the suite of approved cases is offered as a scope", suite.count() == 1);
        suite.click();
        Locator start = page.getByRole(AriaRole.BUTTON, named("Start fixture run"));
        check("Start fixture run is enabled", start.isEnabled());
        shot(page, "clinic-02-ready-to-start");
        start.click();

        page.getByTestId("run-progress").waitFor();
        Locator status = page.getByTestId("run-status").first();
        for (int i = 0; i < 300 && !List.of("SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED").contains(status.getAttribute("data-value")); i++) page.waitForTimeout(2000);
        page.waitForTimeout(2000);
        String end = status.getAttribute("data-value");
        check("the run started from the Dashboard ended: " + end, "SUCCEEDED".equals(end));
        page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName(Pattern.compile("^Steps"))).click();
        Locator rows = page.getByTestId("step-row");
        rows.first().waitFor();
        long scripted = rows.allInnerTexts().stream().filter(t -> t.toLowerCase().contains("script")).count();
        long passed = rows.allInnerTexts().stream().filter(t -> t.toLowerCase().contains("script") && t.contains("Pass")).count();
        check("the Dashboard shows the scripted steps the worker ran: " + scripted + " (" + passed + " passed)", scripted > 0 && passed > 0);
        shot(page, "clinic-03-run-steps");
        page.getByRole(AriaRole.TAB, named("Evidence")).click();
        Locator cards = page.getByText(Pattern.compile("^Screenshot, step \\d+$"));
        cards.first().waitFor();
        page.locator("main img").first().waitFor();
        // The tab loads every screenshot at once: give the first time to arrive before judging that it did.
        boolean loaded;
        try {
            page.waitForFunction("() => { const i = document.querySelector('main img'); return !!i && i.complete && i.naturalWidth > 0; }", null,
                    new Page.WaitForFunctionOptions().setTimeout(20000));
            loaded = true;
        } catch (com.microsoft.playwright.TimeoutError e) {
            loaded = false;
        }
        check("the run's screenshots are shown: " + cards.count() + " on the tab, the first one loaded", cards.count() > 0 && loaded);
        shot(page, "clinic-04-run-evidence");
    }
}
