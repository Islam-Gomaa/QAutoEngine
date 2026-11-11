package qaengine.core.auth;

import qaengine.core.policy.PolicyException;

/**
 * How to fill and check one login form. With no field locators the first form on the login page
 * that has a password field is used, as before; success is never assumed either way, because the
 * flow always verifies with the success path, the success element, or both.
 *
 * @param username       optional even when the others are set: some forms ask for a password only
 * @param successElement visible only when signed in, such as an account menu
 * @param failureElement the page's own sign-in error, which makes a refusal certain rather than inferred
 */
public record LoginForm(LocatorSpec username, LocatorSpec password, LocatorSpec submit, LocatorSpec successElement, LocatorSpec failureElement,
                        int timeoutSeconds) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 15;

    public LoginForm {
        if (timeoutSeconds == 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        if (timeoutSeconds < 5 || timeoutSeconds > 60) throw new PolicyException("The sign-in timeout must be between 5 and 60 seconds");
        if ((username != null || password != null || submit != null) && (password == null || submit == null)) {
            throw new PolicyException("Set both the password field and the sign-in button, or neither");
        }
    }

    public static LoginForm automatic() {
        return new LoginForm(null, null, null, null, null, DEFAULT_TIMEOUT_SECONDS);
    }

    /** True when the fields are found by configured locators rather than by looking for the first login form. */
    public boolean configured() {
        return password != null;
    }
}
