package qaengine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.Redactor;
import qaengine.core.model.Control;
import qaengine.core.model.Navigation;
import qaengine.core.model.PageSnapshot;
import qaengine.core.model.Signal;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.ActionRequest;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionMode;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.RunBudget;
import qaengine.core.policy.TargetScope;
import qaengine.core.port.BrowserPort;
import qaengine.core.verdict.VerdictEngine;

/** RunBudget.maxDepth used to be validated and then ignored. These pin down that it now limits exploration. */
class DepthBudgetTest {

    static final String SITE = "http://site.test";

    /** A site described as page path to the paths it links to. Every page is its own state. */
    static final class ScriptedBrowser implements BrowserPort {
        final Map<String, List<String>> links;
        final Set<String> opened = new LinkedHashSet<>();
        String current = "";

        ScriptedBrowser(Map<String, List<String>> links) {
            this.links = links;
        }

        @Override public Navigation open(String url) {
            opened.add(url.substring(SITE.length()));
            current = url;
            return links.containsKey(url.substring(SITE.length())) ? Navigation.loaded(200, url) : Navigation.loaded(404, url);
        }

        @Override public PageSnapshot snapshot() {
            List<Control> controls = new ArrayList<>();
            for (String to : links.getOrDefault(current.substring(SITE.length()), List.of())) {
                controls.add(new Control(ActionRequest.Kind.NAVIGATE_LINK, SITE + to, to, Map.of(), List.of("a[href=\"" + to + "\"]")));
            }
            return new PageSnapshot(current, current, controls, current);
        }

        @Override public void activate(Control control) { }
        @Override public List<Signal> drainSignals() { return List.of(); }
        @Override public Navigation submitLogin(String username, String password, String oneTimeCode) { throw new UnsupportedOperationException(); }
        @Override public void importCookies(String name, String value, String url) { }
        @Override public byte[] screenshot(List<String> maskSelectors) { return new byte[0]; }
        @Override public String domSnapshot() { return ""; }
        @Override public void close() { }
    }

    /** /p0 -> /p1 -> ... -> /p5 */
    static Map<String, List<String>> chain() {
        return Map.of("/p0", List.of("/p1"), "/p1", List.of("/p2"), "/p2", List.of("/p3"), "/p3", List.of("/p4"), "/p4", List.of("/p5"), "/p5", List.of());
    }

    static RunResult explore(ScriptedBrowser browser, int maxDepth) {
        var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, TargetScope.of(SITE), List.of(), Set.of(),
                new RunBudget(500, 200, maxDepth, Duration.ofMinutes(5), 1));
        var gate = new ActionGate(policy, Clock.systemUTC(), line -> { });
        var evidence = new EvidenceCollector((type, step, content) -> null, new Redactor(List.of()), 1 << 20);
        return new ExplorationEngine(gate, browser, new VerdictEngine(policy.scope()), evidence, 7, Clock.systemUTC(), List.of()).explore(SITE + "/p0");
    }

    @Test
    void pagesDeeperThanTheBudgetAreNeverOpened() {
        var browser = new ScriptedBrowser(chain());
        RunResult result = explore(browser, 2);
        assertEquals(List.of("/p0", "/p1", "/p2"), List.copyOf(browser.opened));
        assertEquals(RunResult.StopReason.DEPTH_LIMIT, result.stop(), "a crawl cut short by depth must not read as finished");
    }

    @Test
    void aBudgetThatCoversTheSiteExploresEverything() {
        var browser = new ScriptedBrowser(chain());
        RunResult result = explore(browser, 5);
        assertEquals(6, browser.opened.size());
        assertEquals(RunResult.StopReason.EXHAUSTED, result.stop());
    }

    @Test
    void aPageIsReachedByItsShortestPath() {
        // /deep is 3 links away through /a, but 1 link away from the start too.
        var site = Map.of("/p0", List.of("/a", "/deep"), "/a", List.of("/b"), "/b", List.of("/deep"), "/deep", List.<String>of());
        var browser = new ScriptedBrowser(site);
        RunResult result = explore(browser, 1);
        assertTrue(browser.opened.contains("/deep"));
        assertFalse(browser.opened.contains("/b"));
        assertEquals(RunResult.StopReason.DEPTH_LIMIT, result.stop());
    }

    @Test
    void frontierCountsTooDeepCandidatesOnceAndForgetsThemWhenAShorterPathAppears() {
        var frontier = new Frontier(1, 2);
        assertFalse(frontier.add(new Candidate(SITE + "/x", 3, null, 0)));
        assertFalse(frontier.add(new Candidate(SITE + "/x#frag", 4, null, 0)));
        assertEquals(1, frontier.beyondDepth());
        assertTrue(frontier.add(new Candidate(SITE + "/x", 2, null, 0)));
        assertEquals(0, frontier.beyondDepth());
        assertFalse(frontier.add(new Candidate(SITE + "/x", 5, null, 0)), "already scheduled");
        assertEquals(0, frontier.beyondDepth(), "a deeper copy of a scheduled page is not a missed page");
    }

    @Test
    void aNegativeDepthIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Frontier(1, -1));
    }
}
