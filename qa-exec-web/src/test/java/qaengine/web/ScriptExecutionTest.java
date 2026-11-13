package qaengine.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.auth.LocatorSpec.Strategy;
import qaengine.core.auth.LoginForm;
import qaengine.core.discovery.PageInventory;
import qaengine.core.discovery.SafetyClass;
import qaengine.core.discovery.SafetyClassifier;
import qaengine.core.engine.ExplorationEngine;
import qaengine.core.engine.LoginFlow;
import qaengine.core.engine.RunResult;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.EvidenceType;
import qaengine.core.evidence.Redactor;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionMode;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.RunBudget;
import qaengine.core.policy.ScopedException;
import qaengine.core.policy.TargetScope;
import qaengine.core.script.FailureClass;
import qaengine.core.script.Script;
import qaengine.core.script.ScriptPlanner;
import qaengine.core.script.ScriptRunner;
import qaengine.core.script.ScriptStep;
import qaengine.core.verdict.Verdict;
import qaengine.core.verdict.VerdictEngine;
import qaengine.fixtures.FixtureServer;

/**
 * Discover, plan, execute, judge, on an application the engine has no code for: the fixture's clinic records, behind
 * its own sign-in. Scripts are planned from the discovered inventory alone, by the same planner the platform uses,
 * and executed through Playwright under the same gate as every run. Then the same is done to the storefront console,
 * which has nothing in common with the clinic, to show neither was written for.
 */
class ScriptExecutionTest {

    static FixtureServer fixture;

    @BeforeAll static void start() throws Exception { fixture = FixtureServer.start(); }
    @AfterAll static void stop() { fixture.close(); }

    record App(String loginPath, String submitPath, String successPath, String start, LoginForm form) {}

    static final App CLINIC = new App("/clinic/login", "/clinic/session", "/clinic/",  "/clinic/",
            new LoginForm(LocatorSpec.of(Strategy.LABEL, "Email"), LocatorSpec.of(Strategy.LABEL, "Password"), new LocatorSpec(Strategy.ROLE, "button", "Sign in"),
                    new LocatorSpec(Strategy.ROLE, "heading", "Dashboard"), null, 10));
    static final App STOREFRONT_CONSOLE = new App("/app/login", "/app/session", "/app/home", "/app/console",
            new LoginForm(LocatorSpec.of(Strategy.LABEL, "Email"), LocatorSpec.of(Strategy.LABEL, "Password"), LocatorSpec.of(Strategy.TEST_ID, "sign-in"),
                    LocatorSpec.of(Strategy.TEST_ID, "account-menu"), null, 10));

    static ActionGate gate(App app, ExecutionMode mode) {
        var login = new ScopedException("login", ScopedException.Purpose.AUTH, fixture.baseUrl(), "POST", app.submitPath(), null, Instant.now().plusSeconds(600), 5, "test",
                ScopedException.ApproverRole.ORGANIZATION_ADMIN);
        var policy = new ExecutionPolicy(mode, EnvironmentClass.LOCAL, TargetScope.of(fixture.baseUrl()), List.of(login), Set.of(),
                new RunBudget(2000, 200, 10, Duration.ofMinutes(10), 2));
        return new ActionGate(policy, Clock.systemUTC(), line -> { });
    }

    static void signIn(App app, ActionGate gate, PlaywrightBrowser b) {
        var spec = new LoginFlow.Spec(LoginFlow.Method.PASSWORD, fixture.baseUrl(), app.loginPath(), app.successPath(), "login", app.form());
        var attempt = new LoginFlow(gate, b).attempt(spec, new LoginFlow.Credentials(FixtureServer.TEST_USER, FixtureServer.TEST_PASSWORD, null, null, null), null);
        assertEquals(LoginFlow.Outcome.SIGNED_IN, attempt.outcome(), "signed in to " + app.start());
    }

    record Discovered(Map<String, PageInventory> pages, RunResult result) {}

    static Discovered discover(App app, ActionGate gate, PlaywrightBrowser b) {
        Map<String, PageInventory> pages = new LinkedHashMap<>();
        var evidence = new EvidenceCollector((type, step, content) -> new qaengine.core.evidence.EvidenceRef(type, step, "", content.length, "memory", qaengine.core.evidence.EvidenceRef.Status.STORED), new Redactor(List.of()), 1 << 20);
        RunResult r = new ExplorationEngine(gate, b, new VerdictEngine(gate.policy().scope()), evidence, 7, Clock.systemUTC(), List.of())
                .sessionEndsAt(app.loginPath())
                .onNewState((step, depth, snapshot) -> {
                    PageInventory inventory = b.describePage();
                    if (inventory != null) pages.putIfAbsent(inventory.url(), inventory);
                })
                .explore(fixture.url(app.start()));
        return new Discovered(pages, r);
    }

    static String path(String url) {
        URI u = URI.create(url);
        return u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    }

    /** Every script the planner makes from what discovery found. Knows inventories, not applications. */
    static Map<String, Script> plan(Map<String, PageInventory> pages) {
        Map<String, Script> out = new LinkedHashMap<>();
        Map<String, PageInventory> byPath = new LinkedHashMap<>();
        pages.values().forEach(p -> byPath.put(path(p.url()), p));
        for (var e : byPath.entrySet()) {
            String from = e.getKey();
            PageInventory p = e.getValue();
            ScriptPlanner.pageOpens(from, p.title(), p.headings()).ifPresent(s -> out.put("page " + from, s));
            for (var t : p.tables()) ScriptPlanner.tableColumns(from, t.headers(), t.rows()).ifPresent(s -> out.put("table " + from + " " + t.caption(), s));
            for (var f : p.forms()) for (var field : f.fields()) {
                if (field.required() && field.labelled() && !field.type().equals("password")) {
                    ScriptPlanner.requiredField(from, field.label()).ifPresent(s -> out.put("required " + from + " " + field.label(), s));
                }
            }
            for (var a : p.actions()) {
                if (SafetyClassifier.classify(a).safety() != SafetyClass.READ_ONLY) continue;
                if ("tab".equals(a.role())) ScriptPlanner.tab(from, a.name()).ifPresent(s -> out.put("tab " + from + " " + a.name(), s));
                if (a.href() != null) {
                    PageInventory to = pages.get(a.href());
                    if (to == null || path(a.href()).equals(from)) continue;
                    String heading = to.headings().isEmpty() ? null : to.headings().getFirst();
                    ScriptPlanner.navigation(from, a.name(), path(a.href()), heading).ifPresent(s -> out.putIfAbsent("nav " + from + " -> " + path(a.href()) + " " + a.name(), s));
                }
            }
        }
        return out;
    }

    record Executed(Map<String, ScriptRunner.Result> results, int screenshots) {}

    static Executed execute(ActionGate gate, PlaywrightBrowser b, String loginPath, Map<String, Script> scripts) {
        AtomicInteger seq = new AtomicInteger();
        List<Integer> shots = new ArrayList<>();
        var evidence = new EvidenceCollector((type, step, content) -> { if (type == EvidenceType.SCREENSHOT) shots.add(step); return new qaengine.core.evidence.EvidenceRef(type, step, "", content.length, "memory", qaengine.core.evidence.EvidenceRef.Status.STORED); }, new Redactor(List.of()), 64 << 20);
        var runner = new ScriptRunner(gate, b, fixture.baseUrl(), loginPath, Duration.ofSeconds(3), evidence, List.of("input[type=password]"), seq::incrementAndGet, Clock.systemUTC());
        Map<String, ScriptRunner.Result> results = new LinkedHashMap<>();
        scripts.forEach((name, s) -> results.put(name, runner.run(s)));
        return new Executed(results, shots.size());
    }

    static String failures(Map<String, ScriptRunner.Result> results) {
        StringBuilder b = new StringBuilder();
        results.forEach((k, r) -> { if (r.verdict() != Verdict.PASS) b.append("\n  ").append(k).append(": ").append(r.verdict()).append(" ").append(r.summary()); });
        return b.toString();
    }

    @Test void theClinicIsDiscoveredPlannedAndPassesWithoutAnythingWrittenForIt() {
        var gate = gate(CLINIC, ExecutionMode.READ_ONLY);
        try (var b = new PlaywrightBrowser(gate)) {
            signIn(CLINIC, gate, b);
            var d = discover(CLINIC, gate, b);
            // Pages behind the Visits tab are only reachable once every patient's tab was used: the crawl must finish, not run out of budget.
            assertTrue(Set.of(RunResult.StopReason.EXHAUSTED, RunResult.StopReason.DEPTH_LIMIT).contains(d.result().stop()), "discovery finished: " + d.result().stop());
            for (String p : List.of("/clinic/", "/clinic/patients", "/clinic/patients/1", "/clinic/appointments", "/clinic/appointments/new",
                    "/clinic/reports", "/clinic/reports/invoices")) {
                assertTrue(d.pages().containsKey(fixture.url(p)), "discovered " + p + " in " + d.pages().keySet());
            }
            // States are told apart by structure, so one patient's visits stand for all: which one depends on the order of the crawl.
            assertTrue(d.pages().keySet().stream().anyMatch(u -> u.contains("/clinic/visits/")), "a visit page, reached through a record's Visits tab");
            assertTrue(d.result().observations().stream().anyMatch(o -> o.finding().url().endsWith("/clinic/reports/monthly")), "the planted server error is found");
            assertFalse(fixture.reached("/clinic/logout"), "signing out is never followed");

            Map<String, Script> scripts = plan(d.pages());
            long kinds = scripts.keySet().stream().map(k -> k.split(" ")[0]).distinct().count();
            assertTrue(scripts.size() >= 20, "planned " + scripts.size());
            assertEquals(5, kinds, "pages, tables, required fields, tabs and navigation: " + scripts.keySet());

            var run = execute(gate, b, CLINIC.loginPath(), scripts);
            assertEquals("", failures(run.results()), "every case planned from what discovery saw holds on the application it was seen on");
            int steps = run.results().values().stream().mapToInt(r -> r.steps().size()).sum();
            assertEquals(steps, run.screenshots(), "one screenshot for every step");
            assertTrue(run.results().containsKey("tab /clinic/patients/1 Visits"));
            assertTrue(run.results().containsKey("required /clinic/appointments/new Reason for visit"));
            assertFalse(fixture.reached("/clinic/logout"), "no script signed out");
        }
    }

    @Test void aReviewedMultiStepWorkflowRunsAndItsExpectationsAreRealAssertions() {
        var gate = gate(CLINIC, ExecutionMode.READ_ONLY);
        try (var b = new PlaywrightBrowser(gate)) {
            signIn(CLINIC, gate, b);
            LocatorSpec visits = new LocatorSpec(Strategy.ROLE, "tab", "Visits");
            // Find a patient by search, open the record, look at the visits, open one: four pages, a query and a tab.
            Script workflow = Script.of(
                    ScriptStep.open("/clinic/patients?q=Amal"),
                    ScriptStep.expectText(new LocatorSpec(Strategy.ROLE, "status", null), "1 patients"),
                    ScriptStep.activate(new LocatorSpec(Strategy.ROLE, "link", "Amal Haddad")),
                    ScriptStep.expectUrl("/clinic/patients/1"),
                    ScriptStep.activate(visits),
                    ScriptStep.expectState(visits, "selected"),
                    ScriptStep.expectVisible(new LocatorSpec(Strategy.ROLE, "cell", "Annual check-up")),
                    ScriptStep.activate(new LocatorSpec(Strategy.ROLE, "link", "2026-10-02")),
                    ScriptStep.expectUrl("/clinic/visits/101"),
                    ScriptStep.expectVisible(new LocatorSpec(Strategy.ROLE, "heading", "Visit on 2026-10-02")));
            Script filter = Script.of(
                    ScriptStep.open("/clinic/patients?status=inactive"),
                    ScriptStep.expectText(new LocatorSpec(Strategy.ROLE, "status", null), "2 patients"),
                    ScriptStep.expectVisible(new LocatorSpec(Strategy.ROLE, "link", "Carla Rivera")),
                    ScriptStep.expectMinCount(new LocatorSpec(Strategy.ROLE, "row", null), 3));
            Script paging = Script.of(
                    ScriptStep.open("/clinic/patients"),
                    ScriptStep.activate(new LocatorSpec(Strategy.ROLE, "link", "Next page")),
                    ScriptStep.expectUrl("/clinic/patients?page=2"),
                    ScriptStep.expectText(null, "Page 2 of 2"));
            // The same workflow with one wrong expectation must fail, at that step, for that reason.
            Script wrong = Script.of(
                    ScriptStep.open("/clinic/patients?q=Amal"),
                    ScriptStep.activate(new LocatorSpec(Strategy.ROLE, "link", "Amal Haddad")),
                    ScriptStep.expectVisible(new LocatorSpec(Strategy.ROLE, "heading", "Brian Okafor")));
            var run = execute(gate, b, CLINIC.loginPath(), new LinkedHashMap<>(Map.of("workflow", workflow, "filter", filter, "paging", paging, "wrong", wrong)));
            var r = run.results();
            assertEquals(Verdict.PASS, r.get("workflow").verdict(), r.get("workflow").summary());
            assertEquals(Verdict.PASS, r.get("filter").verdict(), r.get("filter").summary());
            assertEquals(Verdict.PASS, r.get("paging").verdict(), r.get("paging").summary());
            assertEquals(Verdict.FAIL, r.get("wrong").verdict());
            assertEquals(FailureClass.UNEXPECTED_BEHAVIOR, r.get("wrong").failure());
            assertEquals(3, r.get("wrong").steps().size(), "it stops at the expectation that failed");
        }
    }

    @Test void whatCannotPassIsClassifiedNotHidden() {
        var readOnly = gate(CLINIC, ExecutionMode.READ_ONLY);
        try (var b = new PlaywrightBrowser(readOnly)) {
            // Not signed in: the application sends the run to its sign-in page.
            var unsigned = execute(readOnly, b, CLINIC.loginPath(), Map.of("record", Script.of(ScriptStep.open("/clinic/patients/1"),
                    ScriptStep.expectVisible(new LocatorSpec(Strategy.ROLE, "heading", "Amal Haddad"))))).results().get("record");
            assertEquals(Verdict.INCONCLUSIVE, unsigned.verdict());
            assertEquals(FailureClass.AUTHENTICATION_FAILURE, unsigned.failure());

            signIn(CLINIC, readOnly, b);
            fixture.clear();
            LocatorSpec cancel = new LocatorSpec(Strategy.ROLE, "button", "Cancel appointment");
            LocatorSpec reminder = LocatorSpec.of(Strategy.LABEL, "Reminder email");
            var r = execute(readOnly, b, CLINIC.loginPath(), new LinkedHashMap<>(Map.of(
                    "cancel", Script.of(ScriptStep.open("/clinic/patients/1"), ScriptStep.activate(new LocatorSpec(Strategy.ROLE, "tab", "Billing")), ScriptStep.activate(cancel),
                            ScriptStep.expectText(null, "cancelled")),
                    "type", Script.of(ScriptStep.open("/clinic/appointments/new"), ScriptStep.fill(reminder, "not-an-email"), ScriptStep.expectInvalid(reminder)),
                    "monthly", Script.of(ScriptStep.open("/clinic/reports/monthly"), ScriptStep.expectVisible(new LocatorSpec(Strategy.ROLE, "heading", "Monthly visits"))),
                    "stale", Script.of(ScriptStep.open("/clinic/patients"), ScriptStep.activate(new LocatorSpec(Strategy.ROLE, "link", "Archived patients")),
                            ScriptStep.expectUrl("/clinic/patients/archived")),
                    "offsite", Script.of(ScriptStep.open("/redirect-external"), ScriptStep.expectTitle("Landing"))))).results();
            assertEquals(Verdict.BLOCKED, r.get("cancel").verdict(), "a button is used as the button it is, whatever the script calls it");
            assertEquals(FailureClass.POLICY_BLOCKED, r.get("cancel").failure());
            assertEquals(Verdict.BLOCKED, r.get("type").verdict(), "nothing is typed in READ_ONLY");
            assertEquals(Verdict.FAIL, r.get("monthly").verdict());
            assertEquals(FailureClass.APPLICATION_DEFECT, r.get("monthly").failure(), r.get("monthly").summary());
            assertEquals(Verdict.INCONCLUSIVE, r.get("stale").verdict());
            assertEquals(FailureClass.AUTOMATION_FAILURE, r.get("stale").failure());
            assertEquals(Verdict.BLOCKED, r.get("offsite").verdict(), "a redirect off the target goes back through the gate, which refuses it");
            assertTrue(fixture.requests().stream().noneMatch(q -> !q.method().equals("GET")), "nothing but reads reached the server: " + fixture.requests());
        }

        // The same typing step is allowed where the environment's policy is VALIDATION_ONLY, and the field's own validation answers.
        var validation = gate(CLINIC, ExecutionMode.VALIDATION_ONLY);
        try (var b = new PlaywrightBrowser(validation)) {
            signIn(CLINIC, validation, b);
            fixture.clear();
            LocatorSpec reminder = LocatorSpec.of(Strategy.LABEL, "Reminder email");
            var format = ScriptPlanner.fieldFormat("/clinic/appointments/new", "Reminder email", "not-an-email").orElseThrow();
            var good = Script.of(ScriptStep.open("/clinic/appointments/new"), ScriptStep.fill(reminder, "patient@example.test"), ScriptStep.expectInvalid(reminder));
            var r = execute(validation, b, CLINIC.loginPath(), new LinkedHashMap<>(Map.of("format", format, "good", good))).results();
            assertEquals(Verdict.PASS, r.get("format").verdict(), r.get("format").summary());
            assertEquals(Verdict.FAIL, r.get("good").verdict(), "a valid address is accepted, so expecting it refused fails");
            assertTrue(fixture.requests().stream().allMatch(q -> q.method().equals("GET")), "typing sent nothing: " + fixture.requests());
        }
    }

    /** No sign-in, and nothing on any page until a script has drawn it from JSON: discovered, planned and run the same way. */
    @Test void aClientRenderedApplicationWithoutSignInIsTestedTheSameWay() {
        var gate = gate(new App("/desk/none", "/desk/none", "/desk/", "/desk/", null), ExecutionMode.READ_ONLY);
        try (var b = new PlaywrightBrowser(gate)) {
            fixture.clear(); // the log is shared with the other tests in this class, and their sign-ins are POSTs
            var d = discover(new App(null, null, null, "/desk/", null), gate, b);
            for (String p : List.of("/desk/", "/desk/tickets", "/desk/kb", "/desk/new", "/desk/kb/passwords")) {
                assertTrue(d.pages().containsKey(fixture.url(p)), "discovered " + p + " in " + d.pages().keySet());
            }
            assertTrue(d.pages().keySet().stream().anyMatch(u -> u.matches(".*/desk/tickets/\\d+")), "a ticket, reached from the grid");
            assertTrue(d.result().observations().stream().anyMatch(o -> o.finding().url().endsWith("/desk/kb/retired")), "the planted broken link is found");
            assertFalse(fixture.reached("/desk/requests"), "the request form is never sent");
            Map<String, Script> scripts = plan(d.pages());
            assertTrue(scripts.size() >= 10, "planned " + scripts.size() + ": " + scripts.keySet());
            var run = execute(gate, b, null, scripts);
            assertEquals("", failures(run.results()), "planned from the drawn pages, judged on them");
            assertTrue(fixture.requests().stream().allMatch(q -> q.method().equals("GET")), "nothing but reads: " + fixture.requests().stream().filter(q -> !q.method().equals("GET")).toList());
            assertTrue(fixture.requests().stream().allMatch(q -> q.path().startsWith("/desk")), "nothing outside the application");
        }
    }

    @Test void theSamePlannerWorksOnAnApplicationWithNothingInCommon() {
        var gate = gate(STOREFRONT_CONSOLE, ExecutionMode.READ_ONLY);
        try (var b = new PlaywrightBrowser(gate)) {
            signIn(STOREFRONT_CONSOLE, gate, b);
            var d = discover(STOREFRONT_CONSOLE, gate, b);
            Map<String, Script> scripts = plan(d.pages());
            assertTrue(scripts.size() >= 10, "planned " + scripts.size() + " from " + d.pages().keySet());
            var run = execute(gate, b, STOREFRONT_CONSOLE.loginPath(), scripts);
            // The console's status tabs are static markup: nothing selects "Invited" when it is used. The planned case
            // finds that, as a failure a person must look at, and it is the only thing on the console that fails.
            Map<String, ScriptRunner.Result> notPassed = new LinkedHashMap<>();
            run.results().forEach((k, r) -> { if (r.verdict() != Verdict.PASS) notPassed.put(k, r); });
            assertFalse(notPassed.isEmpty(), "the static tab is found");
            for (var e : notPassed.entrySet()) {
                assertTrue(e.getKey().startsWith("tab ") && e.getKey().endsWith(" Invited"), "only the static tab fails:" + failures(notPassed));
                assertEquals(Verdict.FAIL, e.getValue().verdict());
                assertEquals(FailureClass.UNEXPECTED_BEHAVIOR, e.getValue().failure());
                assertEquals("is not selected", e.getValue().steps().getLast().note());
            }
            assertTrue(run.results().values().stream().filter(r -> r.verdict() == Verdict.PASS).count() >= 10);
        }

        // A required field that loads with a value in it: read-only, it is still marked required; emptied, it refuses.
        var validation = gate(STOREFRONT_CONSOLE, ExecutionMode.VALIDATION_ONLY);
        try (var b = new PlaywrightBrowser(validation)) {
            signIn(STOREFRONT_CONSOLE, validation, b);
            fixture.clear();
            var emptied = ScriptPlanner.requiredRefusesEmpty("/app/console/users/1/edit", "Name").orElseThrow();
            var marked = ScriptPlanner.requiredField("/app/console/users/1/edit", "Name").orElseThrow();
            var r = execute(validation, b, STOREFRONT_CONSOLE.loginPath(), new LinkedHashMap<>(Map.of("emptied", emptied, "marked", marked))).results();
            assertEquals(Verdict.PASS, r.get("emptied").verdict(), r.get("emptied").summary());
            assertEquals(Verdict.PASS, r.get("marked").verdict(), r.get("marked").summary());
            assertTrue(fixture.requests().stream().allMatch(q -> q.method().equals("GET")), "emptying a field sent nothing");
        }
    }
}
