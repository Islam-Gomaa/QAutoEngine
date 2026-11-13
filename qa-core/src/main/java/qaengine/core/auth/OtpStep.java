package qaengine.core.auth;

import java.time.Duration;
import qaengine.core.policy.PolicyException;

/**
 * The one-time code part of a sign-in. Codes are never guessed or retried blindly: one code is
 * submitted per attempt, and a new one is requested only through a configured resend button, up to
 * the configured number of times.
 *
 * @param field             one input, or several that take one character each; empty means the usual code fields
 * @param submit            the button that sends the code; empty means the form's submit button
 * @param failureElement    the page's own error for a wrong or expired code, which makes a refusal certain
 * @param resend            the button that sends a new code; with maxResends 0 it is never pressed
 * @param maxWait           how long to wait for a code before giving up
 * @param exceptionId       the AUTH exception that lets the code submission through the gate
 * @param resendExceptionId the AUTH exception for the resend request, or null when there is none
 */
public record OtpStep(LocatorSpec field, LocatorSpec submit, LocatorSpec failureElement, LocatorSpec resend, int maxResends, Duration maxWait,
                      String exceptionId, String resendExceptionId) {

    public OtpStep {
        if (maxResends < 0 || maxResends > 3) throw new PolicyException("Resends must be between 0 and 3");
        if (maxResends > 0 && resend == null) throw new PolicyException("Set the resend button to allow resends");
        if (maxWait == null || maxWait.compareTo(Duration.ofSeconds(10)) < 0 || maxWait.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new PolicyException("The wait for a code must be between 10 seconds and 15 minutes");
        }
        if (exceptionId == null || exceptionId.isBlank()) throw new PolicyException("The code submission needs its own sign-in permission");
    }
}
