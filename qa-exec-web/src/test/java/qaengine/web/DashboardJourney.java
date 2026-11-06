package qaengine.web;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The product acceptance journey: every capability the dashboard offers, driven in a real browser
 * against the running stack and the local fixture. Screenshots go to docs/screenshots.
 */
public final class DashboardJourney {

    private static final String BASE = "https://localhost:8443";
    private static final Path SHOTS = Path.of("docs/screenshots");
    private static final List<String> failures = new ArrayList<>();

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures.add(name);
    }

    private static Page.GetByRoleOptions named(String name) {
        return new Page.GetByRoleOptions().setName(name).setExact(true);
    }

    private static Locator.GetByRoleOptions inner(String name) {
        return new Locator.GetByRoleOptions().setName(name).setExact(true);
    }

    private static Page.GetByLabelOptions exact() {
        return new Page.GetByLabelOptions().setExact(true);
    }

    private static void noXss(Page page, String where) {
        check("no script injection on " + where, page.evaluate("() => window.__xss === undefined").equals(Boolean.TRUE));
    }

    private static void shot(Page page, String name) {
        page.waitForTimeout(500);
        page.screenshot(new Page.ScreenshotOptions().setPath(SHOTS.resolve(name + ".png")).setFullPage(true));
    }

    private static void nav(Page page, String label) {
        page.getByRole(AriaRole.NAVIGATION, named("Main")).getByRole(AriaRole.LINK, inner(label)).click();
    }

    private static void choose(Page page, String label, String option) {
        Locator area = page.getByRole(AriaRole.DIALOG).count() > 0 ? page.getByRole(AriaRole.DIALOG) : page.locator("main");
        area.getByLabel(java.util.regex.Pattern.compile("^" + label + "\\s*\\*?$")).click();
        page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName(option)).first().click();
    }

    private static void application(Page page, String name, String type, String environment) {
        nav(page, "Applications & Environments");
        page.getByRole(AriaRole.BUTTON, named("New application")).first().click();
        page.getByLabel("Application name").fill(name);
        if (type.equals("API")) choose(page, "Application type", "API");
        page.getByRole(AriaRole.BUTTON, named("Create application")).click();
        var card = page.getByTestId("application-card").filter(new Locator.FilterOptions().setHasText(name));
        card.getByRole(AriaRole.BUTTON, inner("Add environment")).click();
        page.getByLabel("Environment name").fill(environment);
        page.getByRole(AriaRole.BUTTON, named("Use local fixture")).click();
        page.getByRole(AriaRole.DIALOG).getByRole(AriaRole.BUTTON, inner("Add environment")).click();
        card.getByText("Local fixture").waitFor();
    }

    private static String runToEnd(Page page) {
        page.getByTestId("run-progress").waitFor();
        var status = page.getByTestId("run-status").first();
        for (int i = 0; i < 120 && !List.of("SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED").contains(status.getAttribute("data-value")); i++) page.waitForTimeout(2000);
        page.waitForTimeout(1500);
        return status.getAttribute("data-value");
    }

    private static void startRun(Page page, String environment, String scope) {
        nav(page, "Test Execution");
        page.getByLabel("Environment", exact()).click();
        page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName(environment)).first().click();
        if (scope != null) {
            page.getByLabel("Scope", exact()).click();
            page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName(scope)).first().click();
        }
        page.getByRole(AriaRole.BUTTON, named("Start fixture run")).click();
    }

    private static void signIn(Page page, String email, String password) {
        page.getByLabel("Email").fill(email);
        page.getByLabel("Password").fill(password);
        page.getByRole(AriaRole.BUTTON, named("Sign in")).click();
    }

    private static void signOut(Page page) {
        page.getByRole(AriaRole.BUTTON, named("Account")).click();
        page.getByRole(AriaRole.MENUITEM, named("Sign out")).click();
        page.getByRole(AriaRole.BUTTON, named("Sign in")).waitFor();
    }

    public static void main(String[] args) throws Exception {
        String password = Files.readString(Path.of("deploy/secrets/admin_password")).trim();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        String project = "Storefront " + tag;
        Files.createDirectories(SHOTS);
        try (Playwright pw = Playwright.create(); Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            BrowserContext context = browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setAcceptDownloads(true).setViewportSize(1440, 900));
            Page page = context.newPage();
            page.setDefaultTimeout(20000);
            try {
                walk(page, password, tag, project);
            } catch (com.microsoft.playwright.PlaywrightException e) {
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(System.getProperty("java.io.tmpdir"), "journey-failure.png")).setFullPage(true));
                check("journey stopped at " + page.url() + ": " + e.getMessage().lines().filter(l -> l.contains("waiting for") || l.contains("Error")).limit(3).toList(), false);
            }
        }
        System.out.println(failures.isEmpty() ? "journey passed" : "journey FAILED: " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static void walk(Page page, String password, String tag, String project) throws Exception {
            List<String> cspErrors = new ArrayList<>();
            List<String> serverErrors = new ArrayList<>();
            page.onConsoleMessage(m -> { if (m.text().contains("Content Security Policy")) cspErrors.add(m.text()); });
            page.onResponse(r -> { if (r.status() >= 500) serverErrors.add(r.status() + " " + r.url()); });

            // 2, 3: open and sign in
            page.navigate(BASE);
            page.getByRole(AriaRole.BUTTON, named("Sign in")).waitFor();
            shot(page, "01-sign-in");
            signIn(page, "admin@qa.local", "wrong-password");
            page.getByText("Sign-in failed.").waitFor();
            check("wrong password is refused", true);
            signIn(page, "admin@qa.local", password);
            page.getByRole(AriaRole.HEADING, named("Dashboard")).waitFor();
            check("admin signs in to the dashboard", true);

            // 4: projects
            nav(page, "Projects");
            page.getByRole(AriaRole.BUTTON, named("New project")).first().click();
            page.getByLabel("Project name").fill(project);
            page.getByLabel("Description").fill("Fixture storefront used for acceptance");
            page.getByRole(AriaRole.BUTTON, named("Create project")).click();
            page.getByRole(AriaRole.HEADING, named(project)).waitFor();
            check("project is created", true);
            page.getByRole(AriaRole.BUTTON, named("Edit")).click();
            page.getByLabel("Description").fill("Local fixture storefront");
            page.getByRole(AriaRole.BUTTON, named("Save changes")).click();
            page.getByText("Local fixture storefront").waitFor();
            check("project can be edited", true);

            // 5: applications
            application(page, "Storefront Web", "WEB", "Fixture Web");
            application(page, "Storefront API", "API", "Fixture API");
            check("web and API fixture applications are configured", page.getByText("Local fixture", new Page.GetByTextOptions().setExact(true)).count() == 2);
            shot(page, "04-applications");

            nav(page, "Credentials");
            page.getByRole(AriaRole.BUTTON, named("New credential")).click();
            page.getByLabel("Credential name").fill("Fixture API token");
            choose(page, "Kind", "API token");
            page.locator("input[type=password]").first().fill("fixture-admin-token-canary-K9");
            page.getByLabel("Low-privilege token (optional)").fill("fixture-user-token-canary-M3");
            page.getByRole(AriaRole.BUTTON, named("Save credential")).click();
            page.getByTestId("credential-row").waitFor();
            check("credential values are not shown", !page.content().contains("canary"));
            nav(page, "Login Profiles");
            page.getByTestId("profile-row").filter(new Locator.FilterOptions().setHasText("Fixture API")).getByRole(AriaRole.BUTTON, inner("Configure")).click();
            choose(page, "Credential", "Fixture API token");
            page.getByRole(AriaRole.BUTTON, named("Save profile")).click();
            page.getByTestId("profile-row").filter(new Locator.FilterOptions().setHasText("Fixture API token")).waitFor();
            check("API login profile is set", true);

            // 8, 10: web run
            startRun(page, "Fixture Web", null);
            page.getByTestId("run-progress").waitFor();
            String web = runToEnd(page);
            check("web fixture run completes: " + web, web.equals("SUCCEEDED"));
            shot(page, "06-run-detail-web");
            check("step log has rows", page.getByTestId("step-row").count() > 3);
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Console errors")).click();
            page.getByText("Console error").first().waitFor();
            check("browser console errors are shown", true);
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Network failures")).click();
            check("network failures are shown", page.getByRole(AriaRole.LINK).filter(new Locator.FilterOptions().setHasText("DEF-")).count() > 0);
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Evidence")).click();
            page.getByTestId("evidence-image").first().waitFor();
            check("run screenshot loads", (Boolean) page.getByTestId("evidence-image").first().evaluate("i => i.decode().then(() => i.naturalWidth > 0)"));
            noXss(page, "web run");

            // 9, 10: API run
            startRun(page, "Fixture API", null);
            String api = runToEnd(page);
            check("API fixture run completes: " + api, api.equals("SUCCEEDED"));
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("API metrics")).click();
            page.getByText("GET /api/v1/slow").first().waitFor();
            check("API timings are shown", true);
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Evidence")).click();
            page.getByTestId("evidence-text").first().waitFor();
            String exchange = page.getByTestId("evidence-text").first().innerText();
            check("API exchange is readable and redacted", exchange.length() > 20 && !exchange.contains("canary"));
            shot(page, "07-run-detail-api");

            // 6, 7: suites and cases
            nav(page, "Test Suites");
            page.getByRole(AriaRole.BUTTON, named("New suite")).first().click();
            page.getByLabel("Suite name").fill("Smoke web");
            page.getByLabel("Module").fill("Storefront");
            page.getByRole(AriaRole.BUTTON, named("Create suite")).click();
            page.getByTestId("suite-row").waitFor();
            page.getByRole(AriaRole.BUTTON, named("New suite")).first().click();
            page.getByLabel("Suite name").fill("Contract API");
            choose(page, "Suite type", "API");
            page.getByLabel("Module").fill("Storefront");
            page.getByRole(AriaRole.BUTTON, named("Create suite")).click();
            page.getByText("Contract API").waitFor();
            check("web and API suites are created", page.getByTestId("suite-row").count() == 2);

            nav(page, "Test Cases");
            page.getByRole(AriaRole.BUTTON, named("New test case")).click();
            page.getByRole(AriaRole.DIALOG).getByLabel("Title").fill("Checkout shows the total");
            page.getByLabel("Step 1 action").fill("Open the cart");
            page.getByLabel("Step 1 expected result").fill("The total is shown");
            page.getByLabel("Tags, comma separated").fill("smoke, checkout");
            page.getByRole(AriaRole.BUTTON, named("Create test case")).click();
            page.getByText("Checkout shows the total").waitFor();
            shot(page, "05-test-cases");
            page.getByText("Checkout shows the total").click();
            page.getByRole(AriaRole.BUTTON, named("Record result")).click();
            page.getByLabel("Notes").fill("Performed by hand");
            page.getByRole(AriaRole.DIALOG).getByRole(AriaRole.BUTTON, inner("Record result")).click();
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Execution history")).click();
            page.getByText("Performed by hand").waitFor();
            check("manual case and result are saved", true);

            // Automated cases into suites, then run the suites.
            for (String[] pair : new String[][] {{"Page loads", "Smoke web"}, {"API: POSITIVE GET", "Contract API"}}) {
                nav(page, "Test Cases");
                page.getByLabel("Search title, module or tag").fill(pair[0]);
                page.waitForTimeout(800);
                page.getByTestId("case-row").first().click();
                page.getByRole(AriaRole.BUTTON, named("Edit")).click();
                choose(page, "Suite", pair[1]);
                page.getByRole(AriaRole.BUTTON, named("Save changes")).click();
                page.getByRole(AriaRole.DIALOG).waitFor(new Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));
            }
            startRun(page, "Fixture Web", "Smoke web");
            String suiteWeb = runToEnd(page);
            long webSteps = page.getByTestId("step-row").count();
            check("web suite repeats only its case: " + suiteWeb + ", " + webSteps + " step(s)", suiteWeb.equals("SUCCEEDED") && webSteps >= 1 && webSteps <= 3);
            startRun(page, "Fixture API", "Contract API");
            String suiteApi = runToEnd(page);
            long apiSteps = page.getByTestId("step-row").count();
            check("API suite repeats only its case: " + suiteApi + ", " + apiSteps + " step(s)", suiteApi.equals("SUCCEEDED") && apiSteps >= 1 && apiSteps <= 3);

            // 11: evidence
            nav(page, "Evidence");
            page.getByTestId("evidence-row").first().waitFor();
            shot(page, "08-evidence");
            choose(page, "Type", "DOM snapshots");
            page.waitForTimeout(800);
            if (page.getByTestId("evidence-row").count() > 0) {
                page.getByRole(AriaRole.BUTTON, named("View")).first().click();
                check("DOM snapshots are download-only", page.getByRole(AriaRole.LINK, named("Download DOM snapshot")).count() == 1 && page.locator("iframe").count() == 0);
                page.keyboard().press("Escape");
            }

            // 12: defects
            nav(page, "Defects");
            page.getByTestId("defect-row").first().waitFor();
            shot(page, "09-defects");
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Page returned a server error")).first().click();
            page.getByTestId("repro-steps").waitFor();
            check("defect shows reproduction steps", true);
            page.getByTestId("evidence-image").first().waitFor();
            choose(page, "Status", "Triaged");
            page.locator("[data-testid=defect-status][data-value=TRIAGED]").waitFor();
            check("defect status changes", true);
            shot(page, "10-defect-detail");
            noXss(page, "defect detail");
            nav(page, "Defects");
            page.getByLabel("Search summary, URL or DEF-number").fill("console");
            page.waitForTimeout(800);
            check("defect search narrows the list", page.getByTestId("defect-row").count() >= 1 && page.getByTestId("defect-row").count() < 5);

            // 13: reports
            nav(page, "Reports");
            page.getByTestId("stat-Executions").waitFor();
            shot(page, "11-reports");
            var excel = page.waitForDownload(() -> page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Export Excel")).click());
            check("Excel report downloads", Files.size(excel.path()) > 2000);
            var csv = page.waitForDownload(() -> page.getByRole(AriaRole.LINK, named("CSV")).nth(1).click());
            check("CSV report downloads", Files.readString(csv.path()).contains(project));
            nav(page, "Analytics");
            page.getByText("Failure hotspots").waitFor();
            shot(page, "12-analytics");

            // 14: users and roles
            String email = "tester-" + tag + "@qa.local";
            nav(page, "Users & Roles");
            page.getByRole(AriaRole.BUTTON, named("New user")).click();
            page.getByLabel("Email").fill(email);
            page.getByLabel("Initial password").fill("journey-password-" + tag);
            page.getByRole(AriaRole.BUTTON, named("Create user")).click();
            page.getByTestId("user-row").filter(new Locator.FilterOptions().setHasText(email)).waitFor();
            shot(page, "13-users");
            nav(page, "Projects");
            page.getByTestId("project-row").filter(new Locator.FilterOptions().setHasText(project)).click();
            page.getByRole(AriaRole.TAB, named("Members")).click();
            page.getByRole(AriaRole.BUTTON, named("Add member")).click();
            choose(page, "User", email);
            choose(page, "Role", "Viewer");
            page.getByRole(AriaRole.DIALOG).getByRole(AriaRole.BUTTON, inner("Add member")).click();
            page.getByRole(AriaRole.CELL, named(email)).waitFor();
            check("user is added to the project", true);

            // Dashboard after real runs
            nav(page, "Dashboard");
            page.getByTestId("recent-runs").waitFor();
            shot(page, "02-dashboard");
            nav(page, "Projects");
            shot(page, "03-projects");

            // Search
            page.getByLabel("Search", exact()).fill("Checkout shows");
            page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName("Checkout shows the total")).first().click();
            page.getByRole(AriaRole.HEADING, named("Checkout shows the total")).waitFor();
            check("global search finds and opens a test case", true);

            // Sign in as the viewer: role limits apply.
            signOut(page);
            check("sign out works", true);
            signIn(page, email, "journey-password-" + tag);
            page.getByRole(AriaRole.HEADING, named("Dashboard")).waitFor();
            var main = page.getByRole(AriaRole.NAVIGATION, new Page.GetByRoleOptions().setName("Main"));
            check("viewer has no administration items", main.getByRole(AriaRole.LINK, inner("Users & Roles")).count() == 0 && main.getByRole(AriaRole.LINK, inner("Credentials")).count() == 0);
            nav(page, "Test Execution");
            page.getByText("Your role can view runs but not start them.").waitFor();
            check("viewer cannot start a run in the UI", page.getByRole(AriaRole.BUTTON, named("Start fixture run")).count() == 0);
            int refused = (Integer) page.evaluate("async () => { const c = document.cookie.match(/XSRF-TOKEN=([^;]+)/); const r = await fetch('/api/v1/users', {headers: {'X-XSRF-TOKEN': c ? decodeURIComponent(c[1]) : ''}}); return r.status; }");
            check("the server refuses the viewer as well: " + refused, refused == 403);
            signOut(page);

            // 15: nothing broke along the way
            check("no CSP violations: " + cspErrors, cspErrors.isEmpty());
            check("no server errors: " + serverErrors, serverErrors.isEmpty());
    }
}
