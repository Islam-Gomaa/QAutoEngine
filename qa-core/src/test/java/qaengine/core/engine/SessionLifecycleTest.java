package qaengine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qaengine.core.engine.ExplorationEngine.Restore;
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
import qaengine.core.verdict.Verdict;
import qaengine.core.verdict.VerdictEngine;

/** Milestone 5: how a run notices its session ended, signs in again once, and stops cleanly after that. */
class SessionLifecycleTest {

    static final String SITE = "http://site.test";

    /** A site whose session ends after a number of signed-in pages, as a timeout would. */
    static class SessionSite implements BrowserPort {
        final Map<String, List<String>> links = new LinkedHashMap<>();
        final Set<String> endsSession = new HashSet<>(), answers401 = new HashSet<>(), sendsToLoginItself = new HashSet<>(), linksToLogin = new HashSet<>();
        final List<String> opened = new ArrayList<>();
        boolean signedIn = true;
        int views;
        int limit = Integer.MAX_VALUE;
        String current = "";

        SessionSite page(String path, String... to) {
            links.put(path, List.of(to));
            return this;
        }

        @Override public Navigation open(String url) {
            String path = url.substring(SITE.length());
            opened.add(path);
            if (path.startsWith("/login")) { current = url; return Navigation.loaded(200, url); }
            if (linksToLogin.contains(path)) return Navigation.redirected(url, SITE + "/login");
            if (signedIn && ++views > limit) signedIn = false;
            if (endsSession.contains(path)) { signedIn = false; return Navigation.redirected(url, SITE + "/login"); }
            if (!signedIn) {
                if (answers401.contains(path)) { current = url; return Navigation.loaded(401, url); }
                if (sendsToLoginItself.contains(path)) { current = SITE + "/login"; return Navigation.loaded(200, SITE + "/login"); }
                return Navigation.redirected(url, SITE + "/login");
            }
            current = url;
            return Navigation.loaded(200, url);
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

    /** Signs in again by starting a fresh session, as LoginFlow would; or reports what it is told to. */
    static final class Keeper implements ExplorationEngine.SessionKeeper {
        final SessionSite site;
        final Restore answer;
        int calls;

        Keeper(SessionSite site, Restore answer) {
            this.site = site;
            this.answer = answer;
        }

        @Override public Restore restore() {
            calls++;
            if (answer == Restore.SIGNED_IN_AGAIN) {
                site.signedIn = true;
                site.views = 0;
            }
            return answer;
        }
    }

    static ExplorationEngine engine(SessionSite site) {
        var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, TargetScope.of(SITE), List.of(), Set.of(), new RunBudget(500, 200, 10, Duration.ofMinutes(5), 2));
        var gate = new ActionGate(policy, Clock.systemUTC(), line -> { });
        var evidence = new EvidenceCollector((type, step, content) -> null, new Redactor(List.of()), 1 << 20);
        return new ExplorationEngine(gate, site, new VerdictEngine(policy.scope()), evidence, 3, Clock.systemUTC(), List.of());
    }

    static SessionSite fivePages() {
        return new SessionSite().page("/home", "/a", "/b", "/c", "/d").page("/a").page("/b").page("/c").page("/d");
    }

    static long notes(RunResult r, String note) {
        return r.steps().stream().filter(s -> s.note().equals(note)).count();
    }

    @Test void aSessionThatTimesOutIsRestoredOnceAndTheRunCarriesOn() {
        var site = fivePages();
        site.limit = 3;
        var keeper = new Keeper(site, Restore.SIGNED_IN_AGAIN);
        RunResult r = engine(site).sessionEndsAt("/login").keepSessionWith(keeper).explore(SITE + "/home");
        assertEquals(1, keeper.calls);
        assertEquals(1, notes(r, "session ended; signed in again"));
        assertTrue(site.opened.containsAll(List.of("/home", "/a", "/b", "/c", "/d")));
        assertEquals(RunResult.StopReason.EXHAUSTED, r.stop(), "every page was explored after signing in again");
    }

    @Test void aSecondExpiryAfterTheOneFreshSignInStopsCleanly() {
        var site = fivePages();
        site.limit = 2;
        var keeper = new Keeper(site, Restore.SIGNED_IN_AGAIN);
        RunResult r = engine(site).sessionEndsAt("/login").keepSessionWith(keeper).explore(SITE + "/home");
        assertEquals(1, keeper.calls, "one fresh sign-in per run, never more");
        assertEquals(RunResult.StopReason.SESSION_EXPIRED, r.stop());
        assertEquals(1, notes(r, "session ended"));
    }

    @Test void aPageThatEndsTheSessionIsTriedOnceMoreThenTheRunStops() {
        var site = new SessionSite().page("/home", "/expire", "/a").page("/a");
        site.endsSession.add("/expire");
        var keeper = new Keeper(site, Restore.SIGNED_IN_AGAIN);
        RunResult r = engine(site).sessionEndsAt("/login").keepSessionWith(keeper).explore(SITE + "/home");
        assertEquals(1, keeper.calls);
        assertEquals(1, notes(r, "visiting this page ends the session"));
        assertEquals(RunResult.StopReason.SESSION_EXPIRED, r.stop());
    }

    @Test void aFailedSignInStopsTheRun() {
        var site = fivePages();
        site.limit = 1;
        var keeper = new Keeper(site, Restore.FAILED);
        RunResult r = engine(site).sessionEndsAt("/login").keepSessionWith(keeper).explore(SITE + "/home");
        assertEquals(1, keeper.calls);
        assertEquals(RunResult.StopReason.SESSION_EXPIRED, r.stop());
    }

    @Test void aPageThatSendsTheBrowserToTheLoginPageItselfIsNoticed() {
        var site = new SessionSite().page("/home", "/reports").page("/reports");
        site.limit = 1;
        site.sendsToLoginItself.add("/reports");
        RunResult r = engine(site).sessionEndsAt("/login").explore(SITE + "/home");
        assertEquals(RunResult.StopReason.SESSION_EXPIRED, r.stop());
    }

    @Test void aPageAnswering401IsNoticed() {
        var site = new SessionSite().page("/home", "/team").page("/team");
        site.limit = 1;
        site.answers401.add("/team");
        RunResult r = engine(site).sessionEndsAt("/login").explore(SITE + "/home");
        assertEquals(RunResult.StopReason.SESSION_EXPIRED, r.stop());
    }

    @Test void aLinkToTheLoginPageWhileStillSignedInIsAFalseAlarm() {
        var site = new SessionSite().page("/home", "/switch-account", "/a").page("/a");
        site.linksToLogin.add("/switch-account");
        var keeper = new Keeper(site, Restore.STILL_SIGNED_IN);
        RunResult r = engine(site).sessionEndsAt("/login").keepSessionWith(keeper).explore(SITE + "/home");
        assertEquals(1, notes(r, "leads to the login page"));
        assertTrue(site.opened.contains("/a"));
        assertFalse(site.opened.contains("/login"), "the login page is not followed into");
        assertEquals(RunResult.StopReason.EXHAUSTED, r.stop());
    }

    @Test void aFalseAlarmDoesNotUseUpTheOneFreshSignIn() {
        // First a link to the login page while still signed in, then the session really times out.
        var site = new SessionSite().page("/home", "/switch-account", "/a", "/b", "/c").page("/a").page("/b").page("/c");
        site.linksToLogin.add("/switch-account");
        site.limit = 3;
        var answers = new java.util.ArrayDeque<>(List.of(Restore.STILL_SIGNED_IN, Restore.SIGNED_IN_AGAIN));
        int[] calls = {0};
        ExplorationEngine.SessionKeeper keeper = () -> {
            calls[0]++;
            Restore answer = answers.poll();
            if (answer == Restore.SIGNED_IN_AGAIN) { site.signedIn = true; site.views = 0; }
            return answer;
        };
        RunResult r = engine(site).sessionEndsAt("/login").keepSessionWith(keeper).explore(SITE + "/home");
        assertEquals(2, calls[0]);
        assertEquals(1, notes(r, "leads to the login page"));
        assertEquals(1, notes(r, "session ended; signed in again"), "the real expiry still got its fresh sign-in");
        assertEquals(RunResult.StopReason.EXHAUSTED, r.stop());
    }

    @Test void theLoginPathIsComparedWholeNotAsAPrefixOrSubstring() {
        // A redirect to /login-help is an ordinary redirect, followed as one.
        var redirecting = new SessionSite() {
            @Override public Navigation open(String url) {
                if (url.endsWith("/redirector")) { opened.add("/redirector"); return Navigation.redirected(url, SITE + "/login-help"); }
                return super.open(url);
            }
        };
        redirecting.page("/home", "/redirector").page("/login-help");
        RunResult r = engine(redirecting).sessionEndsAt("/login").explore(SITE + "/home");
        assertEquals(RunResult.StopReason.EXHAUSTED, r.stop());
        assertTrue(redirecting.opened.contains("/login-help"));

        // With the login page at "/", only "/" itself counts: before, every redirect did.
        var rooted = new SessionSite() {
            @Override public Navigation open(String url) {
                if (url.endsWith("/old")) { opened.add("/old"); return Navigation.redirected(url, SITE + "/new"); }
                return super.open(url);
            }
        };
        rooted.page("/home", "/old").page("/new");
        assertEquals(RunResult.StopReason.EXHAUSTED, engine(rooted).sessionEndsAt("/").explore(SITE + "/home").stop());
        assertTrue(rooted.opened.contains("/new"));
    }

    @Test void aRunWithoutSignInFollowsRedirectsToTheLoginPageLikeAnyOther() {
        var site = new SessionSite().page("/home", "/a").page("/a");
        site.signedIn = false;
        RunResult r = engine(site).explore(SITE + "/home");
        assertEquals(RunResult.StopReason.EXHAUSTED, r.stop());
        assertTrue(site.opened.contains("/login"));
    }
}
