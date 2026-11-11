package qaengine.core.auth;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import qaengine.core.evidence.EvidenceRef;

/**
 * What one sign-in attempt established, and if it failed, at which stage and why. It holds no
 * secret: the detail is written from fixed text and what the browser reported.
 *
 * @param suggestions locators for "username", "password" and "submit" read from the login page
 *                    without typing anything, so a profile can be set up without guessing
 */
public record AuthenticationResult(Status status, Failure failure, Stage stage, String detail, Duration duration,
                                   Map<String, LocatorSpec> suggestions, List<EvidenceRef> evidence) {

    /** The lifecycle states. WAITING_FOR_OTP is used once email OTP sign-in exists. */
    public enum Status { NOT_STARTED, IN_PROGRESS, WAITING_FOR_OTP, AUTHENTICATED, FAILED, EXPIRED, REQUIRES_USER_ACTION }

    /** OTP is the wait for, and the submission of, a one-time code. */
    public enum Stage { LOGIN_PAGE, CREDENTIALS, SUBMIT, OTP, VERIFY, DONE }

    /**
     * Why it failed, in terms a tester can act on. The OTP codes are reserved for email OTP sign-in.
     */
    public enum Failure {
        /** DNS did not resolve, or the connection was refused, reset or timed out. */
        NETWORK_UNREACHABLE,
        /** The certificate or TLS handshake was rejected. */
        TLS_ERROR,
        /** The gate or the egress proxy refused the request, or the page tried to leave the authorized origin. */
        POLICY_REFUSED,
        /** The login page answered with an error, or its form could not be found. */
        LOGIN_PAGE_UNAVAILABLE,
        /** The target refused the username and password. */
        INVALID_CREDENTIALS,
        /** Submitting did not lead anywhere the configured success checks accept. */
        VERIFICATION_FAILED,
        /** An imported session cookie was not accepted. */
        SESSION_REJECTED,
        /** The run's sign-in attempts are used up, to protect the account from lockout. */
        ATTEMPT_LIMIT,
        OTP_NOT_RECEIVED, OTP_EXTRACTION_FAILED, OTP_INVALID
    }

    public AuthenticationResult {
        suggestions = suggestions == null ? Map.of() : Map.copyOf(suggestions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public boolean authenticated() {
        return status == Status.AUTHENTICATED;
    }
}
