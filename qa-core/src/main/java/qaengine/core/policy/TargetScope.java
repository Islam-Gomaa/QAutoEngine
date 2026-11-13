package qaengine.core.policy;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exact origins and path limits for a target. Hosts are compared exactly; substring matching is
 * how the prototype accepted lookalike domains.
 */
public record TargetScope(Set<String> origins, List<String> includePaths, List<String> excludePaths) {

    public TargetScope {
        origins = origins.stream().map(TargetScope::requireExactOrigin).collect(Collectors.toUnmodifiableSet());
        includePaths = List.copyOf(includePaths);
        excludePaths = List.copyOf(excludePaths);
        if (origins.isEmpty()) throw new PolicyException("A target needs at least one allowed origin");
    }

    public static TargetScope of(String... origins) {
        return new TargetScope(Set.of(origins), List.of(), List.of());
    }

    private static String requireExactOrigin(String origin) {
        if (origin.contains("*")) throw new PolicyException("Wildcard origins are not allowed: " + origin);
        return originOf(origin).orElseThrow(() -> new PolicyException("Invalid origin: " + origin));
    }

    /** Normalized scheme://host:port, or empty when the URL is not a plain http(s) URL. */
    public static Optional<String> originOf(String url) {
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || uri.getRawUserInfo() != null) return Optional.empty();
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("ws") && !scheme.equals("wss")) {
                return Optional.empty();
            }
            String base = scheme.startsWith("ws") ? scheme.replace("ws", "http") : scheme;
            int port = uri.getPort() != -1 ? uri.getPort() : (base.equals("https") ? 443 : 80);
            return Optional.of(base + "://" + host.toLowerCase(Locale.ROOT) + ":" + port);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public boolean allowsHost(String url) {
        return originOf(url).filter(origins::contains).isPresent();
    }

    public boolean allowsPath(String url) {
        String path;
        try {
            path = new URI(url.trim()).normalize().getPath();
        } catch (Exception e) {
            return false;
        }
        if (path == null) return false;
        String p = path.isEmpty() ? "/" : path;
        if (excludePaths.stream().anyMatch(x -> matches(p, x))) return false;
        return includePaths.isEmpty() || includePaths.stream().anyMatch(x -> matches(p, x));
    }

    private static boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
    }
}
