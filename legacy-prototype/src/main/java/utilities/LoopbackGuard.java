package utilities;

import java.net.URI;
import java.util.Set;

/**
 * Phase 1 containment. The legacy engine has no safety policy, so it may only
 * ever talk to loopback. There is deliberately no override.
 */
public final class LoopbackGuard {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private LoopbackGuard() {}

    public static boolean isLoopback(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && host != null
                    && LOOPBACK.contains(host.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    public static void requireLoopback(String url) {
        if (!isLoopback(url)) {
            throw new SecurityException("Legacy prototype is contained to loopback; refused: " + url);
        }
    }
}
