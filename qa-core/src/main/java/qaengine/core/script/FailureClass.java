package qaengine.core.script;

/**
 * Why a scripted case did not pass, as far as the evidence shows. Decided by fixed rules from what was observed;
 * where the evidence cannot tell two causes apart, the class says so rather than picking one.
 */
public enum FailureClass {
    /** The application answered with a server error or an error page. Seen, not inferred. */
    APPLICATION_DEFECT,
    /** An expectation did not hold. Either the application changed or the expectation is wrong: a person decides. */
    UNEXPECTED_BEHAVIOR,
    /** The script could not find the element it was told to use. The locator may be stale. */
    AUTOMATION_FAILURE,
    /** The page could not be reached at all: network, DNS, TLS, proxy. Says nothing about the application. */
    ENVIRONMENT_FAILURE,
    /** The session ended, or the page sent the run to sign in. */
    AUTHENTICATION_FAILURE,
    /** Waiting for the page or an element ran out of time. */
    TIMEOUT,
    /** The gate refused the action. Coverage that policy, not the application, prevented. */
    POLICY_BLOCKED,
    /** The run ended before the script could finish, for example when it was cancelled. */
    INSUFFICIENT_EVIDENCE
}
