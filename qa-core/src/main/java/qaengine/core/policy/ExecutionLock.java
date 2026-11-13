package qaengine.core.policy;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Where any executor may go. Loopback and the local fixture are built in. A real host may be reached only when an
 * operator approved it, host and port, in the deployment's approved-hosts file ({@link ApprovedHosts}): the
 * infrastructure key. That is still not enough to run: the platform also requires a written authorization in date
 * for the environment, recorded by an Organization Admin (the product key), and the egress proxy enforces the same
 * file on the network. Nothing in the product can approve a host; there is no switch, only the file.
 */
public final class ExecutionLock {

    /** The planted-defect fixture when it runs as a container on the test network. Never a real system. */
    public static final String FIXTURE_HOST = "fixture.qa.internal";

    private static final Set<String> BUILT_IN = Set.of("127.0.0.1", "localhost", "[::1]", "::1", FIXTURE_HOST);

    /** Set once at start by the platform and the worker, from the same mounted file. Empty until then: fixture only. */
    private static volatile ApprovedHosts approved = ApprovedHosts.none();

    private ExecutionLock() {}

    public static void configure(ApprovedHosts hosts) {
        approved = hosts == null ? ApprovedHosts.none() : hosts;
    }

    public static ApprovedHosts approved() {
        return approved;
    }

    /** Loopback or the fixture: built in, and the only places a secret may travel without TLS to the target. */
    public static boolean isLocal(String url) {
        String host = URI.create(url).getHost();
        return host != null && BUILT_IN.contains(host);
    }

    /** The built-in hosts, or a real host and port the operator approved. Says nothing about authorization. */
    public static boolean isApproved(String url) {
        if (isLocal(url)) return true;
        URI u = URI.create(url);
        String host = u.getHost();
        if (host == null) return false;
        int port = u.getPort() >= 0 ? u.getPort() : "http".equals(u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT)) ? 80 : 443;
        return approved.allows(host, port);
    }

    /** A password sent to an http:// target would be readable at the proxy and on every hop after it. */
    public static void requireSecureForSecrets(String url) {
        if (!url.startsWith("https://") && !isLocal(url)) {
            throw new SecurityException("Refusing to send credentials over plain HTTP: " + url);
        }
    }

    public static void requireAllowed(TargetScope scope) {
        for (String origin : scope.origins()) {
            if (!isApproved(origin)) {
                throw new SecurityException("Real targets are locked until the pre-pilot security gate passes: " + origin);
            }
        }
    }
}
