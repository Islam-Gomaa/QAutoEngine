package qaengine.web;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Phase 9 acceptance: the whole journey in a real browser, against the running stack. */
public final class DashboardJourney {

    private static final String BASE = "https://localhost:8443";
    private static final List<String> failures = new ArrayList<>();

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures.add(name);
    }

    private static Page.GetByRoleOptions named(String name) {
        return new Page.GetByRoleOptions().setName(name).setExact(true);
    }

    private static void noXss(Page page, String where) {
        check("no script injection on " + where, page.evaluate("() => window.__xss === undefined").equals(Boolean.TRUE));
    }

    public static void main(String[] args) throws Exception {
        String password = Files.readString(Path.of("deploy/secrets/admin_password")).trim();
        String project = "journey-" + UUID.randomUUID().toString().substring(0, 8);
        try (Playwright pw = Playwright.create(); Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            var context = browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
            Page page = context.newPage();
            List<String> cspErrors = new ArrayList<>();
            page.onConsoleMessage(m -> { if (m.text().contains("Content Security Policy")) cspErrors.add(m.text()); });

            page.navigate(BASE);
            page.getByLabel("Email").fill("admin@qa.local");
            page.getByLabel("Password").fill("wrong-password");
            page.getByRole(AriaRole.BUTTON, named("Sign in")).click();
            page.getByText("Sign-in failed.").waitFor();
            check("wrong password is refused", true);

            page.getByLabel("Password").fill(password);
            page.getByRole(AriaRole.BUTTON, named("Sign in")).click();
            page.getByText("Projects", new Page.GetByTextOptions().setExact(true)).waitFor();
            check("sign in works", true);

            page.getByLabel("New project name").fill(project);
            page.getByRole(AriaRole.BUTTON, named("Create project")).click();
            page.getByRole(AriaRole.TAB, named("Targets")).click();
            page.getByLabel("Target name").fill("Fixture");
            page.getByRole(AriaRole.BUTTON, named("Add target")).click();
            page.waitForTimeout(500);
            page.getByLabel("Target", new Page.GetByLabelOptions().setExact(true)).click();
            page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName("Fixture")).click();
            page.getByLabel("Environment name").fill("local");
            page.getByLabel("Base URL").fill("http://fixture.qa.internal:8090/");
            page.getByRole(AriaRole.BUTTON, named("Add environment")).click();
            page.getByText("READ_ONLY").waitFor();
            check("new environment defaults to READ_ONLY", true);

            page.getByRole(AriaRole.TAB, named("Runs")).click();
            page.getByLabel("Environment", new Page.GetByLabelOptions().setExact(true)).click();
            page.getByRole(AriaRole.OPTION).first().click();
            page.getByRole(AriaRole.BUTTON, named("Start read-only run")).click();
            var status = page.getByTestId("run-status").first();
            status.waitFor();
            for (int i = 0; i < 150 && !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status.innerText()); i++) page.waitForTimeout(2000);
            check("run finishes from the dashboard: " + status.innerText(), status.innerText().equals("SUCCEEDED"));

            page.getByRole(AriaRole.TAB, named("Defects")).click();
            page.getByTestId("defect-row").first().waitFor();
            int defects = page.getByTestId("defect-row").count();
            check("all 7 planted defects shown: " + defects, defects == 7);
            noXss(page, "defect list");

            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Page returned a server error")).click();
            page.getByText("Steps to reproduce").waitFor();
            var image = page.getByTestId("evidence-image").first();
            image.waitFor();
            page.waitForTimeout(800);
            check("screenshot evidence loads", (Integer) image.evaluate("i => i.naturalWidth") > 0);
            check("linked test case shown", page.getByTestId("linked-cases").innerText().endsWith("1"));
            noXss(page, "defect detail");

            page.goBack();
            page.getByRole(AriaRole.TAB, named("Defects")).click();
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Console error")).first().click();
            page.waitForTimeout(600);
            noXss(page, "console error defect");
            page.goBack();
            page.getByRole(AriaRole.TAB, named("Overview")).click();
            page.getByText("Open defects", new Page.GetByTextOptions().setExact(true)).waitFor();
            check("overview shows the stored count", page.getByText("7", new Page.GetByTextOptions().setExact(true)).count() >= 1);

            var popup = page.waitForPopup(() -> page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Latest report (HTML, printable)")).click());
            popup.waitForLoadState();
            check("HTML report opens", popup.content().contains("Defects"));
            check("report escapes target text", !popup.content().contains("<img src=x"));
            noXss(popup, "report");
            popup.close();

            var download = page.waitForDownload(() -> page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Latest report (Excel)")).click());
            check("Excel report downloads", download.suggestedFilename().endsWith(".xlsx"));

            check("no Content Security Policy violations: " + cspErrors, cspErrors.isEmpty());

            page.getByRole(AriaRole.BUTTON, named("Sign out")).click();
            page.getByRole(AriaRole.BUTTON, named("Sign in")).waitFor();
            check("sign out works", true);
        }
        System.out.println(failures.isEmpty() ? "journey passed" : "journey FAILED: " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}
