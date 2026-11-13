package qaengine.core.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.auth.LocatorSpec.Strategy;
import qaengine.core.model.Control;
import qaengine.core.model.Navigation;
import qaengine.core.model.PageSnapshot;
import qaengine.core.model.Signal;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.PolicyException;
import qaengine.core.policy.TargetScope;
import qaengine.core.port.BrowserPort;
import qaengine.core.verdict.Verdict;

class ScriptTest {

    static final LocatorSpec HEADING = new LocatorSpec(Strategy.ROLE, "heading", "Orders");

    @Test
    void aScriptOpensAPageOfItsOwnEnvironmentAndExpectsSomething() {
        assertEquals(2, Script.of(ScriptStep.open("/orders?page=2"), ScriptStep.expectVisible(HEADING)).steps().size());
        assertThrows(PolicyException.class, () -> Script.of(ScriptStep.expectVisible(HEADING)), "it starts by opening a page");
        assertThrows(PolicyException.class, () -> Script.of(ScriptStep.open("/orders")), "opening proves nothing");
        assertThrows(PolicyException.class, () -> new Script(List.of()).validated());
        List<ScriptStep> many = new ArrayList<>(List.of(ScriptStep.open("/")));
        for (int i = 0; i < Script.MAX_STEPS; i++) many.add(ScriptStep.expectVisible(HEADING));
        assertThrows(PolicyException.class, () -> new Script(many).validated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.test/", "//evil.test/x", "orders", "/orders#frag", "/orders\nx", "javascript:alert(1)", ""})
    void onlyPathsOnTheEnvironmentCanBeOpened(String path) {
        assertThrows(PolicyException.class, () -> Script.of(ScriptStep.open(path), ScriptStep.expectVisible(HEADING)));
        assertThrows(PolicyException.class, () -> Script.of(ScriptStep.open("/"), ScriptStep.expectUrl(path)));
    }

    @Test
    void everyStepIsCheckedBeforeItCanRun() {
        assertThrows(PolicyException.class, () -> Script.of(ScriptStep.open("/"), ScriptStep.expectMinCount(HEADING, 0)));
        assertThrows(PolicyException.class, () -> Script.of(ScriptStep.open("/"), ScriptStep.expectState(HEADING, "checked")));
        assertThrows(PolicyException.class, () -> Script.of(ScriptStep.open("/"), ScriptStep.expectText(null, " ")));
        assertThrows(PolicyException.class, () -> Script.of(ScriptStep.open("/"), ScriptStep.fill(LocatorSpec.of(Strategy.LABEL, "Name"), "a\u0000b"), ScriptStep.expectVisible(HEADING)));
        assertThrows(PolicyException.class, () -> Script.of(ScriptStep.open("/"), new ScriptStep(ScriptStep.Op.ACTIVATE, null, null, null, null), ScriptStep.expectVisible(HEADING)));
        assertThrows(PolicyException.class, () -> Script.of(ScriptStep.open("/"), new ScriptStep(ScriptStep.Op.EXPECT_TITLE, "/x", null, "T", null)));
        // A locator smuggled in without its own checks is rebuilt, and refused.
        assertThrows(PolicyException.class, () -> Script.of(ScriptStep.open("/"), ScriptStep.expectVisible(new LocatorSpec(Strategy.ROLE, "banana", "x"))));
        assertTrue(Script.of(ScriptStep.open("/"), ScriptStep.fill(LocatorSpec.of(Strategy.LABEL, "Name"), ""), ScriptStep.expectInvalid(LocatorSpec.of(Strategy.LABEL, "Name")))
                .steps().size() == 3, "emptying a field is a valid step");
    }

    @Test
    void thePlannerMakesNothingFromFactsItCannotUse() {
        assertTrue(ScriptPlanner.pageOpens("/x", "", List.of()).isEmpty(), "no title, no heading: nothing to expect");
        assertTrue(ScriptPlanner.navigation("/a", " ", "/b", null).isEmpty());
        assertTrue(ScriptPlanner.tableColumns("/a", List.of(), 0).isEmpty());
        assertTrue(ScriptPlanner.requiredField("/a", "").isEmpty());
        assertTrue(ScriptPlanner.pageOpens("https://elsewhere.test/", "T", List.of()).isEmpty(), "an invalid path is left for a person");
        assertEquals(1 + ScriptPlanner.MAX_COLUMNS + 1, ScriptPlanner.tableColumns("/a", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), 3).orElseThrow().steps().size());
    }

    /** A browser on a script, recording what it was asked to do. */
    static final class Fake implements BrowserPort {
        final Map<String, Navigation> pages = new HashMap<>();
        final Map<LocatorSpec, Map<String, String>> elements = new HashMap<>();
        final List<String> did = new ArrayList<>();
        String url = "about:blank";
        RuntimeException failOnOpen;

        @Override public Navigation open(String u) {
            did.add("open " + u);
            if (failOnOpen != null) throw failOnOpen;
            Navigation n = pages.getOrDefault(u, Navigation.loaded(200, u));
            if (n.outcome() == Navigation.Outcome.LOADED) url = u;
            return n;
        }
        @Override public String currentUrl() { return url; }
        @Override public boolean waitVisible(LocatorSpec e, Duration t) { return elements.containsKey(e); }
        @Override public Map<String, String> inspect(LocatorSpec e) { return elements.getOrDefault(e, Map.of()); }
        @Override public void click(LocatorSpec e) { did.add("click " + e.describe()); }
        @Override public void fill(LocatorSpec f, String v) { did.add("fill " + f.describe()); }
        @Override public String title() { return "Orders"; }
        @Override public PageSnapshot snapshot() { return null; }
        @Override public void activate(Control control) { }
        @Override public List<Signal> drainSignals() { return List.of(); }
        @Override public Navigation submitLogin(String u, String p, String c) { return null; }
        @Override public void importCookies(String n, String v, String u) { }
        @Override public byte[] screenshot(List<String> masks) { return new byte[0]; }
        @Override public String domSnapshot() { return ""; }
        @Override public void close() { }
    }

    static ScriptRunner.Result run(Fake b, Script s) {
        var gate = new ActionGate(ExecutionPolicy.readOnly(EnvironmentClass.QA, TargetScope.of("https://app.test")), Clock.systemUTC(), l -> { });
        AtomicInteger seq = new AtomicInteger();
        return new ScriptRunner(gate, b, "https://app.test/", "/login", Duration.ZERO, null, List.of(), seq::incrementAndGet, Clock.systemUTC()).run(s);
    }

    @Test
    void anElementIsUsedAsWhatItIsNotWhatTheScriptCallsIt() {
        Fake b = new Fake();
        LocatorSpec innocent = new LocatorSpec(Strategy.ROLE, "tab", "Details");
        // The page says this "tab" is a button labelled Delete. The gate is asked about the button.
        b.elements.put(innocent, Map.of("tag", "button", "role", "", "text", "Delete order", "inForm", "false"));
        var r = run(b, Script.of(ScriptStep.open("/orders"), ScriptStep.activate(innocent), ScriptStep.expectTitle("Orders")));
        assertEquals(Verdict.BLOCKED, r.verdict());
        assertEquals(FailureClass.POLICY_BLOCKED, r.failure());
        assertFalse(b.did.stream().anyMatch(d -> d.startsWith("click")), "never clicked");

        Fake real = new Fake();
        real.elements.put(innocent, Map.of("tag", "button", "role", "tab", "text", "Details", "aria-selected", "false"));
        assertEquals(Verdict.PASS, run(real, Script.of(ScriptStep.open("/orders"), ScriptStep.activate(innocent), ScriptStep.expectTitle("Orders"))).verdict());
        assertTrue(real.did.contains("click role tab named \"Details\""));
    }

    @Test
    void aLinkIsFollowedThroughTheGateAndOffTargetLinksAreRefused() {
        Fake b = new Fake();
        LocatorSpec away = new LocatorSpec(Strategy.ROLE, "link", "Partner");
        b.elements.put(away, Map.of("tag", "a", "href", "https://partner.test/", "text", "Partner"));
        var r = run(b, Script.of(ScriptStep.open("/"), ScriptStep.activate(away), ScriptStep.expectTitle("Orders")));
        assertEquals(Verdict.BLOCKED, r.verdict());
        assertFalse(b.did.contains("open https://partner.test/"));
    }

    @Test
    void whatCannotBeJudgedIsNeverAPass() {
        Fake signedOut = new Fake();
        signedOut.pages.put("https://app.test/orders", Navigation.redirected("https://app.test/orders", "https://app.test/login"));
        assertEquals(FailureClass.AUTHENTICATION_FAILURE, run(signedOut, Script.of(ScriptStep.open("/orders"), ScriptStep.expectTitle("Orders"))).failure());

        Fake unreachable = new Fake();
        unreachable.pages.put("https://app.test/orders", Navigation.failed("https://app.test/orders", "net::ERR_NAME_NOT_RESOLVED"));
        var r = run(unreachable, Script.of(ScriptStep.open("/orders"), ScriptStep.expectTitle("Orders")));
        assertEquals(Verdict.INCONCLUSIVE, r.verdict());
        assertEquals(FailureClass.ENVIRONMENT_FAILURE, r.failure());
        assertEquals(2, unreachable.did.size(), "an unreachable page is tried once more, and only once");

        Fake broken = new Fake();
        broken.failOnOpen = new IllegalStateException("browser crashed");
        var crashed = run(broken, Script.of(ScriptStep.open("/orders"), ScriptStep.expectTitle("Orders")));
        assertEquals(Verdict.INCONCLUSIVE, crashed.verdict());
        assertEquals(FailureClass.AUTOMATION_FAILURE, crashed.failure());

        Fake error = new Fake();
        error.pages.put("https://app.test/orders", Navigation.loaded(503, "https://app.test/orders"));
        var defect = run(error, Script.of(ScriptStep.open("/orders"), ScriptStep.expectTitle("Orders")));
        assertEquals(Verdict.FAIL, defect.verdict());
        assertEquals(FailureClass.APPLICATION_DEFECT, defect.failure());
        assertEquals(1, defect.steps().size(), "nothing after a failed step runs");
    }

    @Test
    void nothingIsTypedInReadOnlyAndSecretFieldsAreNeverTyped() {
        Fake b = new Fake();
        LocatorSpec name = LocatorSpec.of(Strategy.LABEL, "Name");
        LocatorSpec password = LocatorSpec.of(Strategy.LABEL, "Password");
        b.elements.put(name, Map.of("tag", "input", "type", "text"));
        b.elements.put(password, Map.of("tag", "input", "type", "password"));
        assertEquals(Verdict.BLOCKED, run(b, Script.of(ScriptStep.open("/"), ScriptStep.fill(name, "x"), ScriptStep.expectInvalid(name))).verdict());
        var secret = run(b, Script.of(ScriptStep.open("/"), ScriptStep.fill(password, "x"), ScriptStep.expectInvalid(password)));
        assertEquals(Verdict.BLOCKED, secret.verdict());
        assertTrue(secret.summary().contains("password field is never filled"));
        assertTrue(b.did.stream().noneMatch(d -> d.startsWith("fill")));
    }

    @Test
    void aStoppedRunEndsTheCaseWithoutAVerdictOnTheApplication() {
        Fake b = new Fake();
        var gate = new ActionGate(ExecutionPolicy.readOnly(EnvironmentClass.QA, TargetScope.of("https://app.test")), Clock.systemUTC(), l -> { });
        var r = new ScriptRunner(gate, b, "https://app.test/", null, Duration.ZERO, null, List.of(), () -> 1, Clock.systemUTC()).stopWhen(() -> true)
                .run(Script.of(ScriptStep.open("/"), ScriptStep.expectTitle("Orders")));
        assertEquals(Verdict.INCONCLUSIVE, r.verdict());
        assertEquals(FailureClass.INSUFFICIENT_EVIDENCE, r.failure());
        assertTrue(b.did.isEmpty());
        assertEquals(Set.of(), Set.copyOf(r.steps()));
    }
}
