package qaengine.web;

import java.net.URI;
import java.util.Set;
import qaengine.core.policy.TargetScope;

/**
 * Keeps every executor on loopback until the pre-pilot security gate passes. There is deliberately
 * no switch here: lifting this is a reviewed code change, not a configuration value.
 */
public final class ExecutionLock {

    /** The planted-defect fixture when it runs as a container on the test network. Never a real system. */
    public static final String FIXTURE_HOST = "fixture.qa.internal";

    private static final Set<String> ALLOWED = Set.of("127.0.0.1", "localhost", "[::1]", "::1", FIXTURE_HOST);

    private ExecutionLock() {}

    public static void requireAllowed(TargetScope scope) {
        for (String origin : scope.origins()) {
            String host = URI.create(origin).getHost();
            if (host == null || !ALLOWED.contains(host)) {
                throw new SecurityException("Real targets are locked until the pre-pilot security gate passes: " + origin);
            }
        }
    }
}
