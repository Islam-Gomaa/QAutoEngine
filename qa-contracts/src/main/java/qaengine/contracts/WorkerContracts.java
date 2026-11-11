package qaengine.contracts;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The internal worker API. Workers see only this; they never touch the database. */
public final class WorkerContracts {

    private WorkerContracts() {}

    /** A claimed job. The token is scoped to this job and attempt. */
    public record JobAssignment(UUID jobId, UUID runId, int attempt, String jobToken, String startUrl, long seed, String mode,
                                String environmentClass, List<String> allowedOrigins, List<String> includePaths,
                                List<String> excludePaths, RunLimits limits, List<String> maskSelectors, LoginProfile login,
                                String channel, ApiProfile api, RunScope scope, String kind) {}

    /** EXPLORATION signs in when a profile is set, then explores. AUTH_TEST signs in, or only connects, and stops. */
    public static final String AUTH_TEST = "AUTH_TEST";

    /** Null for a full exploration. Otherwise only these saved cases run. */
    public record RunScope(List<String> urls, List<String> labels) {}

    /** How to test an API. The spec path is relative to the environment's base URL. */
    public record ApiProfile(String specPath, long slowMillis, List<String> seedPaths) {}

    /** How to find one element on the target's page. See qaengine.core.auth.LocatorSpec. */
    public record Locator(String strategy, String value, String name) {}

    /**
     * How to sign in. Holds no secret: the worker fetches that once, after claiming the job.
     * Without field locators the first login form on the page is used; success is always checked.
     */
    public record LoginProfile(String method, String loginPath, String submitPath, String successPath,
                               Locator usernameField, Locator passwordField, Locator submitButton, Locator successElement, Locator failureElement,
                               int timeoutSeconds, OtpProfile otp) {
        public LoginProfile(String method, String loginPath, String submitPath, String successPath) {
            this(method, loginPath, submitPath, successPath, null, null, null, null, null, 0, null);
        }
    }

    /**
     * The one-time code step. The code itself comes from the platform, one poll at a time.
     *
     * @param waitSeconds how long the worker waits for a code; the platform's own limits end the wait sooner
     */
    public record OtpProfile(Locator field, Locator submitButton, Locator failureElement, Locator resendButton, String submitPath, String resendPath,
                             int maxResends, int waitSeconds) {}

    /** One poll for a code. Either a code, a failure name with fixed-text detail, or a wait. */
    public record OtpReply(String code, String failure, String detail, int retryAfterSeconds, boolean waitingForPerson) {}

    /**
     * One sign-in attempt or connection check, as the worker saw it. Holds no secret.
     *
     * @param status      a lifecycle state, or CONNECTED when only the connection was checked
     * @param suggestions locators read from the login page without typing, by "username", "password" and "submit"
     */
    public record AuthReport(String status, String failure, String stage, String detail, long durationMs, Map<String, Locator> suggestions) {}

    /** Returned only to the running attempt, and never stored by the worker. */
    public record LoginSecret(String username, String password, String oneTimeCode, String sessionCookieName, String sessionCookieValue, String lowPrivilegeToken) {}

    public record RunLimits(int maxActions, int maxPages, int maxDepth, int maxMinutes, int maxLoginAttempts) {}

    public record HeartbeatResponse(boolean cancelRequested, String jobToken) {}

    public record StepReport(int sequence, int parent, String kind, String url, String label, List<String> locators,
                             String verdict, int status, String state, String note, long durationMs) {}

    public record FindingReport(int step, String fingerprint, String type, String severity, String url, String title,
                                String detail, List<String> evidenceSha256) {}

    public record RunCompletion(String outcome, String stopReason, int steps, Map<String, String> states) {}
}
