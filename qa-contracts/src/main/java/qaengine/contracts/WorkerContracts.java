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
                                List<String> excludePaths, RunLimits limits, List<String> maskSelectors, LoginProfile login) {}

    /** How to sign in. Holds no secret: the worker fetches that once, after claiming the job. */
    public record LoginProfile(String method, String loginPath, String submitPath, String successPath) {}

    /** Returned only to the running attempt, and never stored by the worker. */
    public record LoginSecret(String username, String password, String oneTimeCode, String sessionCookieName, String sessionCookieValue) {}

    public record RunLimits(int maxActions, int maxPages, int maxDepth, int maxMinutes, int maxLoginAttempts) {}

    public record HeartbeatResponse(boolean cancelRequested, String jobToken) {}

    public record StepReport(int sequence, int parent, String kind, String url, String label, List<String> locators,
                             String verdict, int status, String state, String note) {}

    public record FindingReport(int step, String fingerprint, String type, String severity, String url, String title,
                                String detail, List<String> evidenceSha256) {}

    public record RunCompletion(String outcome, String stopReason, int steps, Map<String, String> states) {}
}
