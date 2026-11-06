package qaengine.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qaengine.core.model.Navigation;
import qaengine.core.model.Signal;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionMode;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.RunBudget;
import qaengine.core.policy.ScopedException;
import qaengine.core.policy.TargetScope;
import qaengine.fixtures.FixtureServer;

/** Phase 3 acceptance: judged by what reached the fixture server. */
class SafetyEnforcementTest {

    static FixtureServer fixture;
    static PlaywrightBrowser browser;
    static ActionGate gate;
    static final List<String> audit = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        fixture = FixtureServer.start();
        var login = new ScopedException("login", ScopedException.Purpose.AUTH, fixture.baseUrl(), "POST", "/session", null,
                Instant.now().plusSeconds(600), 5, "test", ScopedException.ApproverRole.PROJECT_ADMIN);
        var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, TargetScope.of(fixture.baseUrl()),
                List.of(login), Set.of(), RunBudget.defaults());
        gate = new ActionGate(policy, Clock.systemUTC(), audit::add);
        browser = new PlaywrightBrowser(gate);
    }

    @AfterAll
    static void stop() {
        browser.close();
        fixture.close();
    }

    @BeforeEach
    void reset() {
        fixture.clear();
        browser.drainSignals();
    }

    private void onPageRun(String page, String script) {
        browser.open(fixture.url(page));
        browser.evaluate(script);
        browser.pause(700);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/items/5/delete", "/items?action=remove&id=5", "/logout", "/verify/9f8e7d6c5b4a39281706f5e4d3c2b1a0", "/download/report.bin"})
    void destructiveGetTrapsNeverReachServer(String path) {
        Navigation n = browser.open(fixture.url(path));
        assertEquals(Navigation.Outcome.BLOCKED, n.outcome());
        assertFalse(fixture.reached(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete-account", "confirm-payment", "remove-user"})
    void buttonTrapsAreBlockedEvenIfClicked(String name) {
        onPageRun("/traps/danger", "fetch('/m/" + name + "',{method:'POST'}).catch(()=>{})");
        assertFalse(fixture.reached("/m/" + name));
        assertTrue(browser.drainSignals().stream().anyMatch(s -> s.type() == Signal.Type.BLOCKED_BY_POLICY));
    }

    @Test
    void formSubmissionAndMethodOverrideAreBlocked() {
        onPageRun("/traps/forms", "document.getElementById('contact').requestSubmit ? document.getElementById('contact').submit() : 0");
        assertFalse(fixture.reached("/submitted"));
        onPageRun("/traps/forms", "document.getElementById('override').submit()");
        assertFalse(fixture.reached("/m/override"));
    }

    @Test
    void graphqlMutationIsBlocked() {
        onPageRun("/traps/forms", "window.gql('mutation Drop { drop }').catch(()=>{})");
        assertFalse(fixture.reached("/graphql"));
    }

    @Test
    void redirectIsReportedAndNotFollowed() {
        Navigation n = browser.open(fixture.url("/redirect-internal"));
        assertEquals(Navigation.Outcome.REDIRECTED, n.outcome());
        assertTrue(n.redirectTarget().endsWith("/m/redirect-target"));
        assertFalse(fixture.reached("/m/redirect-target"));
    }

    @Test
    void hostileRedirectToOtherHostIsNotFollowed() {
        Navigation n = browser.open(fixture.url("/redirect-external"));
        assertEquals(Navigation.Outcome.REDIRECTED, n.outcome());
        assertEquals(Navigation.Outcome.BLOCKED, browser.open(n.redirectTarget()).outcome());
    }

    @Test
    void crossHostLinkIsBlocked() {
        assertEquals(Navigation.Outcome.BLOCKED, browser.open("http://blocked.invalid/").outcome());
    }

    @Test
    void confirmDialogIsDismissed() {
        onPageRun("/about", "window.__r = confirm('Sure?')");
        assertEquals(Boolean.FALSE, browser.evaluate("return window.__r"));
        assertTrue(browser.drainSignals().stream().anyMatch(s -> s.type() == Signal.Type.DIALOG_DISMISSED));
    }

    @Test
    void webSocketIsBlocked() {
        onPageRun("/about", "new WebSocket(location.origin.replace('http','ws')+'/ws-attempt')");
        assertFalse(fixture.reached("/ws-attempt"));
    }

    @Test
    void authWindowOpensForLoginThenCloses() {
        browser.open(fixture.url("/login"));
        String body = "'username=" + FixtureServer.TEST_USER + "&password=" + FixtureServer.TEST_PASSWORD + "'";
        String post = "fetch('/session',{method:'POST',body:" + body + "}).catch(()=>{})";
        browser.evaluate(post);
        browser.pause(400);
        assertFalse(fixture.reached("/session"), "window closed");
        assertTrue(gate.openAuthWindow("login").allowed());
        browser.evaluate(post);
        browser.pause(400);
        assertTrue(fixture.reached("/session"), "window open");
        gate.closeAuthWindow();
        fixture.clear();
        browser.evaluate(post);
        browser.pause(400);
        assertFalse(fixture.reached("/session"), "closed again");
    }

    @Test
    void residualRiskIsDocumentedNotClaimedSafe() {
        // An unmarked destructive GET cannot be detected in advance. This records that honestly.
        Navigation nav = browser.open(fixture.url("/tools/nightly"));
        assertEquals(Navigation.Outcome.LOADED, nav.outcome(), nav.detail());
        assertTrue(fixture.reached("/tools/nightly"));
    }
}
