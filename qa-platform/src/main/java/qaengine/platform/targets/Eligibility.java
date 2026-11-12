package qaengine.platform.targets;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.function.Predicate;
import qaengine.core.policy.ExecutionLock;

/**
 * Whether an environment's target may be run, and if not, which key is missing. The one place the API
 * and run creation decide this, so they cannot disagree.
 *
 * <p>A real target needs every key: its host approved in {@link ExecutionLock} by a reviewed code change,
 * and a written authorization that is still in date. The fixture needs neither. The fixture is named here
 * explicitly rather than taken from {@code ExecutionLock}: when a real host is one day approved there, it
 * must still need an authorization, not pass as the fixture.
 *
 * <p>Whether the environment and its application are active is reported separately, as it always was.
 */
public final class Eligibility {

    public enum Reason {
        /** Loopback or the planted-defect fixture. Runs are allowed. */
        FIXTURE,
        /** Every key is present. Unreachable until a real host is approved in ExecutionLock. */
        ELIGIBLE,
        /** The host is not approved in ExecutionLock: the pre-pilot security gate. */
        HOST_NOT_APPROVED,
        /** No written authorization is recorded. */
        NOT_AUTHORIZED,
        /** The recorded authorization has expired. */
        AUTHORIZATION_EXPIRED
    }

    /** @param lockReason null when the target may be run */
    public record Result(boolean executable, Reason reason) {
        public Reason lockReason() {
            return executable ? null : reason;
        }
    }

    /** The only hosts that are the fixture. Each must also be allowed by ExecutionLock, which a test checks. */
    public static final Set<String> FIXTURE_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]", "::1", ExecutionLock.FIXTURE_HOST);

    private Eligibility() {}

    public static boolean isFixture(String url) {
        return isFixture(url, ExecutionLock::isLocal);
    }

    private static boolean isFixture(String url, Predicate<String> approved) {
        String host = URI.create(url).getHost();
        return host != null && FIXTURE_HOSTS.contains(host) && approved.test(url);
    }

    /**
     * @param authorizationReference null when no authorization is recorded
     * @param authorizationExpiresAt when the recorded authorization ends
     */
    public static Result of(String baseUrl, String authorizationReference, OffsetDateTime authorizationExpiresAt, Instant now) {
        return of(baseUrl, authorizationReference, authorizationExpiresAt, now, ExecutionLock::isLocal);
    }

    /** The same decision with host approval passed in, so tests can reach the branches no approved real host reaches yet. */
    static Result of(String baseUrl, String authorizationReference, OffsetDateTime authorizationExpiresAt, Instant now, Predicate<String> approved) {
        if (isFixture(baseUrl, approved)) return new Result(true, Reason.FIXTURE);
        if (!approved.test(baseUrl)) return new Result(false, Reason.HOST_NOT_APPROVED);
        if (authorizationReference == null || authorizationExpiresAt == null) return new Result(false, Reason.NOT_AUTHORIZED);
        if (!authorizationExpiresAt.toInstant().isAfter(now)) return new Result(false, Reason.AUTHORIZATION_EXPIRED);
        return new Result(true, Reason.ELIGIBLE);
    }

    /** What run creation says when it refuses. The gate's wording is unchanged from before. */
    public static String refusal(Reason reason) {
        return switch (reason) {
            case HOST_NOT_APPROVED -> "Real targets are locked until the pre-pilot security gate passes";
            case NOT_AUTHORIZED -> "This environment has no written authorization";
            case AUTHORIZATION_EXPIRED -> "This environment's authorization has expired";
            case FIXTURE, ELIGIBLE -> throw new IllegalArgumentException("not a refusal");
        };
    }
}
