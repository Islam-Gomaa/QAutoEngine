package qaengine.core.auth;

import java.time.Duration;

/**
 * Where one-time codes come from. The worker asks the platform, which reads a dedicated test mailbox or
 * takes a code a person typed into the dashboard; tests read the fixture. Implementations never log a code.
 */
public interface OtpSource {

    /** Called just before an action that makes the target send a code, so no code sent earlier is accepted. */
    void requested();

    /** Called once when the flow starts waiting, so a person can be asked to help. */
    void waiting();

    /** One check for a code. */
    Reply poll();

    /** A code, a reason to stop, or neither: wait and ask again. */
    record Reply(String code, AuthenticationResult.Failure failure, String detail, Duration retryAfter) {
        public static Reply waitFor(Duration retryAfter) {
            return new Reply(null, null, null, retryAfter);
        }

        public static Reply code(String code) {
            return new Reply(code, null, null, Duration.ZERO);
        }

        public static Reply stop(AuthenticationResult.Failure failure, String detail) {
            return new Reply(null, failure, detail, Duration.ZERO);
        }
    }
}
