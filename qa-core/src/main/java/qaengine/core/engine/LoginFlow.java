package qaengine.core.engine;

import qaengine.core.model.Navigation;
import qaengine.core.policy.ActionGate;
import qaengine.core.port.BrowserPort;

/**
 * Signs in using only the methods the product supports. Anything else, including push or SMS
 * approval and CAPTCHA, ends as BLOCKED: MFA is never worked around.
 */
public final class LoginFlow {

    public enum Method { PASSWORD, TOTP, SESSION_IMPORT }

    public enum Outcome { SIGNED_IN, BLOCKED, FAILED }

    public record Spec(Method method, String baseUrl, String loginPath, String successPath, String authExceptionId) {}

    public record Credentials(String username, String password, String oneTimeCode, String cookieName, String cookieValue) {}


    private final ActionGate gate;
    private final BrowserPort browser;

    public LoginFlow(ActionGate gate, BrowserPort browser) {
        this.gate = gate;
        this.browser = browser;
    }

    public Outcome signIn(Spec spec, Credentials credentials) {
        qaengine.core.policy.ExecutionLock.requireSecureForSecrets(spec.baseUrl());
        if (spec.method() == Method.SESSION_IMPORT) {
            browser.importCookies(credentials.cookieName(), credentials.cookieValue(), spec.baseUrl());
            return verified(spec);
        }
        if (!gate.openAuthWindow(spec.authExceptionId()).allowed()) return Outcome.BLOCKED;
        try {
            Navigation page = browser.open(spec.baseUrl() + spec.loginPath());
            if (page.outcome() != Navigation.Outcome.LOADED) return Outcome.FAILED;
            String code = spec.method() == Method.TOTP ? credentials.oneTimeCode() : null;
            Navigation result = browser.submitLogin(credentials.username(), credentials.password(), code);
            if (result.outcome() == Navigation.Outcome.FAILED) return Outcome.FAILED;
            if (result.outcome() == Navigation.Outcome.LOADED && result.status() >= 400) return Outcome.FAILED;
        } finally {
            gate.closeAuthWindow();
        }
        return verified(spec);
    }

    /** The session proves itself by reaching a page that needs it. Anywhere else means it is not ours. */
    private Outcome verified(Spec spec) {
        Navigation check = browser.open(spec.baseUrl() + spec.successPath());
        if (check.outcome() == Navigation.Outcome.LOADED && check.status() < 400) return Outcome.SIGNED_IN;
        return check.outcome() == Navigation.Outcome.REDIRECTED ? Outcome.BLOCKED : Outcome.FAILED;
    }

}
