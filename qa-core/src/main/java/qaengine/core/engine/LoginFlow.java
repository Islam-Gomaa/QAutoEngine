package qaengine.core.engine;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import qaengine.core.auth.AuthenticationResult;
import qaengine.core.auth.AuthenticationResult.Failure;
import qaengine.core.auth.AuthenticationResult.Stage;
import qaengine.core.auth.AuthenticationResult.Status;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.auth.LoginForm;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.EvidenceRef;
import qaengine.core.evidence.EvidenceType;
import qaengine.core.model.Navigation;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.ActionRequest;
import qaengine.core.policy.Decision;
import qaengine.core.policy.ExecutionLock;
import qaengine.core.policy.TargetScope;
import qaengine.core.port.BrowserPort;

/**
 * Signs in using only the methods the product supports. Anything else, including push or SMS
 * approval and CAPTCHA, ends as BLOCKED: MFA is never worked around. Success is never assumed: it
 * is what the configured success checks see after submitting, and a failure says which stage
 * failed and why, in terms a tester can act on.
 */
public final class LoginFlow {

    public enum Method { PASSWORD, TOTP, SESSION_IMPORT }

    /** The decision callers act on. BLOCKED means the session is not ours, or the gate or attempt limit stopped it. */
    public enum Outcome { SIGNED_IN, BLOCKED, FAILED }

    /** @param form how to fill and check the form; the five-argument form keeps the original behaviour */
    public record Spec(Method method, String baseUrl, String loginPath, String successPath, String authExceptionId, LoginForm form) {
        public Spec {
            form = form == null ? LoginForm.automatic() : form;
        }

        public Spec(Method method, String baseUrl, String loginPath, String successPath, String authExceptionId) {
            this(method, baseUrl, loginPath, successPath, authExceptionId, LoginForm.automatic());
        }
    }

    public record Credentials(String username, String password, String oneTimeCode, String cookieName, String cookieValue) {}

    /** Where a screenshot and the browser log of the attempt go, and the run step they belong to. */
    public record Evidence(EvidenceCollector collector, int step) {}

    public record Attempt(AuthenticationResult result, Outcome outcome) {}

    private static final LocatorSpec ANY_PASSWORD = LocatorSpec.of(LocatorSpec.Strategy.CSS, "input[type=password]");
    private static final LocatorSpec ANY_CODE = LocatorSpec.of(LocatorSpec.Strategy.CSS, "input[autocomplete=one-time-code], input[name=code], input[name=otp], input[name=totp]");
    private static final List<String> MASKS = List.of("input[type=password]", "input[autocomplete=one-time-code]", "input[autocomplete=username]", "input[type=email]");
    private static final int MAX_REDIRECTS = 3;

    private final ActionGate gate;
    private final BrowserPort browser;

    public LoginFlow(ActionGate gate, BrowserPort browser) {
        this.gate = gate;
        this.browser = browser;
    }

    public Outcome signIn(Spec spec, Credentials credentials) {
        return attempt(spec, credentials, null).outcome();
    }

    /** One sign-in attempt, classified, with masked evidence when a collector is given. */
    public Attempt attempt(Spec spec, Credentials credentials, Evidence evidence) {
        ExecutionLock.requireSecureForSecrets(spec.baseUrl());
        long started = System.nanoTime();
        State state = new State();
        browser.drainSignals();
        Ending end;
        try {
            end = run(spec, credentials, state);
        } catch (RuntimeException e) {
            // A browser error must never pass for a result: it is reported as the stage that failed, without its message.
            end = new Ending(Status.FAILED, state.stage == Stage.LOGIN_PAGE ? Failure.LOGIN_PAGE_UNAVAILABLE : Failure.VERIFICATION_FAILED,
                    "The browser reported an error during " + state.stage.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '), Outcome.FAILED);
        }
        List<EvidenceRef> refs = evidence == null ? List.of() : capture(evidence, spec.form());
        Stage stage = end.status() == Status.AUTHENTICATED ? Stage.DONE : state.stage;
        var result = new AuthenticationResult(end.status(), end.failure(), stage, end.detail(), Duration.ofNanos(System.nanoTime() - started), state.suggestions, refs);
        return new Attempt(result, end.outcome());
    }

    /**
     * Checks only that the application answers through the gate, for an environment with no sign-in.
     * Success is NOT_STARTED with no failure: connected, and nothing was signed in.
     */
    public AuthenticationResult connect(String baseUrl, Evidence evidence) {
        long started = System.nanoTime();
        browser.drainSignals();
        Spec spec = new Spec(Method.PASSWORD, baseUrl, "", null, null);
        Ending end;
        try {
            end = pageProblem(openWithinOrigin(spec, baseUrl), spec);
        } catch (RuntimeException e) {
            end = failed(Failure.LOGIN_PAGE_UNAVAILABLE, "The browser reported an error while connecting");
        }
        List<EvidenceRef> refs = evidence == null ? List.of() : capture(evidence, LoginForm.automatic());
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        if (end == null) return new AuthenticationResult(Status.NOT_STARTED, null, Stage.DONE, "The application answered; no sign-in is configured", took, Map.of(), refs);
        return new AuthenticationResult(end.status(), end.failure(), Stage.LOGIN_PAGE, end.detail().replace("The login page", "The application"), took, Map.of(), refs);
    }

    private static final class State {
        Stage stage = Stage.LOGIN_PAGE;
        Map<String, LocatorSpec> suggestions = Map.of();
    }

    private record Ending(Status status, Failure failure, String detail, Outcome outcome) {
        static Ending ok() {
            return new Ending(Status.AUTHENTICATED, null, "Signed in, and the success checks passed", Outcome.SIGNED_IN);
        }
    }

    private static Ending failed(Failure failure, String detail) {
        return new Ending(Status.FAILED, failure, detail, Outcome.FAILED);
    }

    private static Ending blocked(Failure failure, String detail) {
        return new Ending(Status.FAILED, failure, detail, Outcome.BLOCKED);
    }

    private Ending run(Spec spec, Credentials c, State state) {
        if (spec.method() == Method.SESSION_IMPORT) {
            browser.importCookies(c.cookieName(), c.cookieValue(), spec.baseUrl());
            state.stage = Stage.VERIFY;
            Ending v = verify(spec, true);
            return v != null ? v : Ending.ok();
        }
        Decision window = gate.openAuthWindow(spec.authExceptionId());
        if (!window.allowed()) {
            return window.reason() == Decision.Reason.BUDGET_EXCEEDED
                    ? blocked(Failure.ATTEMPT_LIMIT, "The sign-in attempts for this run are used up, to protect the account from lockout")
                    : blocked(Failure.POLICY_REFUSED, "Sign-in is not permitted for this target");
        }
        try {
            Ending problem = signInWithinWindow(spec, c, state);
            if (problem != null) return problem;
        } finally {
            gate.closeAuthWindow();
        }
        state.stage = Stage.VERIFY;
        Ending v = verify(spec, false);
        return v != null ? v : Ending.ok();
    }

    private Ending signInWithinWindow(Spec spec, Credentials c, State state) {
        state.stage = Stage.LOGIN_PAGE;
        Navigation page = openWithinOrigin(spec, spec.baseUrl() + spec.loginPath());
        Ending problem = pageProblem(page, spec);
        if (problem != null) return problem;
        if (!sameOrigin(browser.currentUrl(), spec.baseUrl())) return blocked(Failure.POLICY_REFUSED, "The login page is on another site, so nothing was typed into it");
        state.suggestions = browser.suggestLoginFields();

        state.stage = Stage.CREDENTIALS;
        LoginForm form = spec.form();
        String code = spec.method() == Method.TOTP ? c.oneTimeCode() : null;
        Navigation submitted;
        if (form.configured()) {
            if (form.username() != null && !browser.isVisible(form.username())) return failed(Failure.LOGIN_PAGE_UNAVAILABLE, "The username field was not found by " + form.username().describe());
            if (!browser.isVisible(form.password())) return failed(Failure.LOGIN_PAGE_UNAVAILABLE, "The password field was not found by " + form.password().describe());
            if (!browser.isVisible(form.submit())) return failed(Failure.LOGIN_PAGE_UNAVAILABLE, "The sign-in button was not found by " + form.submit().describe());
            if (code != null && !browser.isVisible(ANY_CODE)) return failed(Failure.LOGIN_PAGE_UNAVAILABLE, "No one-time code field was found");
            if (!gate.decideAction(new ActionRequest(ActionRequest.Kind.FORM_FILL, null, Map.of())).allowed()) return blocked(Failure.POLICY_REFUSED, "The gate refused to fill the form");
            if (form.username() != null) browser.fill(form.username(), c.username());
            browser.fill(form.password(), c.password());
            if (code != null) browser.fill(ANY_CODE, code);
            state.stage = Stage.SUBMIT;
            if (!gate.decideAction(new ActionRequest(ActionRequest.Kind.FORM_SUBMIT, null, Map.of())).allowed()) return blocked(Failure.POLICY_REFUSED, "The gate refused to submit the form");
            submitted = browser.submitWith(form.submit(), Duration.ofSeconds(form.timeoutSeconds()));
        } else {
            state.stage = Stage.SUBMIT;
            submitted = browser.submitLogin(c.username(), c.password(), code);
        }
        return afterSubmit(submitted, spec);
    }

    /** Follows redirects that stay on the target's origin, each through the gate. Leaving the origin is reported, not followed. */
    private Navigation openWithinOrigin(Spec spec, String url) {
        Navigation nav = browser.open(url);
        for (int i = 0; i < MAX_REDIRECTS && nav.outcome() == Navigation.Outcome.REDIRECTED && sameOrigin(nav.redirectTarget(), spec.baseUrl()); i++) {
            if (!gate.decideAction(new ActionRequest(ActionRequest.Kind.NAVIGATE_LINK, nav.redirectTarget(), Map.of())).allowed()) {
                return Navigation.blocked(nav.redirectTarget(), "redirect target refused");
            }
            nav = browser.open(nav.redirectTarget());
        }
        return nav;
    }

    private Ending pageProblem(Navigation page, Spec spec) {
        return switch (page.outcome()) {
            case BLOCKED -> blocked(Failure.POLICY_REFUSED, "The gate refused the login page");
            case FAILED -> network(page.detail(), "The login page did not load");
            case REDIRECTED -> sameOrigin(page.redirectTarget(), spec.baseUrl())
                    ? failed(Failure.LOGIN_PAGE_UNAVAILABLE, "The login page kept redirecting; set the login path to the page it settles on")
                    : blocked(Failure.POLICY_REFUSED, "The login page redirects to another site, which is outside the authorized scope");
            case LOADED -> page.status() >= 400 ? failed(Failure.LOGIN_PAGE_UNAVAILABLE, "The login page answered HTTP " + page.status()) : null;
        };
    }

    /** Names the network problem from the browser's error code. The rest of the browser message is not repeated. */
    private static Ending network(String detail, String otherwise) {
        String d = detail == null ? "" : detail;
        if (d.contains("ERR_NAME_NOT_RESOLVED") || d.contains("ERR_NAME_RESOLUTION_FAILED")) return failed(Failure.NETWORK_UNREACHABLE, "The host name did not resolve (DNS)");
        if (d.contains("ERR_CERT_") || d.contains("ERR_SSL_") || d.contains("ERR_BAD_SSL")) return failed(Failure.TLS_ERROR, "The TLS connection or certificate was rejected");
        if (d.contains("ERR_TUNNEL_CONNECTION_FAILED") || d.contains("ERR_PROXY_")) return blocked(Failure.POLICY_REFUSED, "The egress proxy refused the host");
        if (d.contains("ERR_BLOCKED_BY_CLIENT")) return blocked(Failure.POLICY_REFUSED, "The gate refused the request");
        for (String code : List.of("ERR_CONNECTION_REFUSED", "ERR_CONNECTION_RESET", "ERR_CONNECTION_CLOSED", "ERR_CONNECTION_TIMED_OUT", "ERR_ADDRESS_UNREACHABLE",
                "ERR_INTERNET_DISCONNECTED", "ERR_NETWORK_CHANGED", "ERR_TIMED_OUT", "ERR_EMPTY_RESPONSE")) {
            if (d.contains(code)) return failed(Failure.NETWORK_UNREACHABLE, "The connection failed (" + code.substring(4).replace('_', ' ').toLowerCase(java.util.Locale.ROOT) + ")");
        }
        if (d.toLowerCase(java.util.Locale.ROOT).contains("timeout")) return failed(Failure.NETWORK_UNREACHABLE, "The page did not answer in time");
        return failed(Failure.LOGIN_PAGE_UNAVAILABLE, otherwise);
    }

    private Ending afterSubmit(Navigation submitted, Spec spec) {
        LoginForm form = spec.form();
        switch (submitted.outcome()) {
            case BLOCKED -> {
                return blocked(Failure.POLICY_REFUSED, "The gate refused the sign-in request; check the form submit path");
            }
            case FAILED -> {
                String d = submitted.detail() == null ? "" : submitted.detail();
                if (d.contains("no password field")) return failed(Failure.LOGIN_PAGE_UNAVAILABLE, "No login form with a password field was found on the page");
                if (d.contains("no one-time code field")) return failed(Failure.LOGIN_PAGE_UNAVAILABLE, "No one-time code field was found");
                if (d.contains("ERR_")) return network(d, "Submitting the form did not complete");
                return failed(Failure.VERIFICATION_FAILED, "Submitting the form did not complete");
            }
            case REDIRECTED -> {
                String target = submitted.redirectTarget();
                if (!sameOrigin(target, spec.baseUrl())) return blocked(Failure.VERIFICATION_FAILED, "Signing in leads to another site, which is outside the authorized scope");
                if (path(target).equals(path(spec.baseUrl() + spec.loginPath()))) return failed(Failure.INVALID_CREDENTIALS, "Signing in led back to the login page, so the credentials were probably refused");
                if (form.successElement() != null && spec.successPath() == null) openWithinOrigin(spec, target);
                return null;
            }
            case LOADED -> {
                int status = submitted.status();
                if (status == 401 || status == 403) return failed(Failure.INVALID_CREDENTIALS, "The target refused the credentials (HTTP " + status + ")");
                if (status >= 500) return failed(Failure.VERIFICATION_FAILED, "The target answered HTTP " + status + " to the sign-in");
                if (status >= 400) return failed(Failure.INVALID_CREDENTIALS, "The target rejected the sign-in (HTTP " + status + ")");
                if (form.failureElement() != null && browser.isVisible(form.failureElement())) return failed(Failure.INVALID_CREDENTIALS, "The page showed its sign-in error, found by " + form.failureElement().describe());
                if (browser.isVisible(form.password() != null ? form.password() : ANY_PASSWORD)) {
                    return failed(Failure.INVALID_CREDENTIALS, "The sign-in form was shown again after submitting, so the credentials were probably refused");
                }
                return null;
            }
        }
        return null;
    }

    /** The session proves itself by reaching a page that needs it, and showing what only a signed-in user sees. */
    private Ending verify(Spec spec, boolean imported) {
        LoginForm form = spec.form();
        if (spec.successPath() == null && form.successElement() == null) return failed(Failure.VERIFICATION_FAILED, "No success check is configured, so sign-in cannot be confirmed");
        if (spec.successPath() != null) {
            Navigation check = browser.open(spec.baseUrl() + spec.successPath());
            if (check.outcome() == Navigation.Outcome.REDIRECTED) {
                return imported ? blocked(Failure.SESSION_REJECTED, "The imported session was not accepted")
                        : blocked(Failure.VERIFICATION_FAILED, "The page after signing in redirected elsewhere, so the session was not accepted");
            }
            if (check.outcome() != Navigation.Outcome.LOADED || check.status() >= 400) {
                return failed(Failure.VERIFICATION_FAILED, "The page after signing in did not load" + (check.status() > 0 ? " (HTTP " + check.status() + ")" : ""));
            }
        }
        if (form.successElement() != null && !browser.appears(form.successElement(), Duration.ofSeconds(form.timeoutSeconds()))) {
            return failed(Failure.VERIFICATION_FAILED, "The signed-in marker was not found by " + form.successElement().describe());
        }
        return null;
    }

    private List<EvidenceRef> capture(Evidence evidence, LoginForm form) {
        List<EvidenceRef> refs = new ArrayList<>();
        try {
            var signals = browser.drainSignals();
            refs.add(evidence.collector().text(EvidenceType.SIGNAL_LOG, evidence.step(), signals::toString));
            List<LocatorSpec> masks = new ArrayList<>();
            if (form.username() != null) masks.add(form.username());
            if (form.password() != null) masks.add(form.password());
            refs.add(evidence.collector().binary(EvidenceType.SCREENSHOT, evidence.step(), browser.screenshot(MASKS, masks)));
        } catch (RuntimeException e) {
            // Evidence is best effort; its absence is visible, and it must never change the result.
        }
        return refs;
    }

    private static boolean sameOrigin(String url, String base) {
        if (url == null) return false;
        var a = TargetScope.originOf(url);
        return a.isPresent() && a.equals(TargetScope.originOf(base));
    }

    private static String path(String url) {
        try {
            String p = URI.create(url).getPath();
            return p == null || p.isEmpty() ? "/" : p.replaceAll("/+$", "");
        } catch (RuntimeException e) {
            return "";
        }
    }
}
