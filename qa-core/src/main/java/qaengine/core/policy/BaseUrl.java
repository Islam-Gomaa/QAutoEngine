package qaengine.core.policy;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one way a target's base URL is accepted. It never resolves DNS or contacts the host: the egress
 * proxy still decides addresses at run time. Messages are fixed text, so the input is never reflected.
 */
public final class BaseUrl {

    private static final int MAX_LENGTH = 2048;

    /** Cloud instance metadata services that answer on a name rather than only on 169.254.169.254. */
    private static final Set<String> METADATA_HOSTS = Set.of(
            "metadata", "metadata.google.internal", "metadata.goog", "instance-data", "instance-data.ec2.internal",
            "metadata.azure.com", "metadata.tencentyun.com", "metadata.platformequinix.com", "metadata.packet.net");

    /** Dotted, decimal, octal and hex IPv4 forms, all of which browsers accept as addresses. */
    private static final Pattern IPV4_LIKE = Pattern.compile("^(0x[0-9a-f]*|[0-9]+)(\\.(0x[0-9a-f]*|[0-9]*))*$");
    private static final Pattern NUMERIC_LABEL = Pattern.compile("^(0x[0-9a-f]*|[0-9]+)$");
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    private BaseUrl() {}

    /**
     * Returns scheme://host[:port]/path in lower-case host form, without a default port, or throws
     * {@link PolicyException} with a reason that is safe to show.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) throw new PolicyException("A base URL is required");
        String text = raw.strip();
        if (text.length() > MAX_LENGTH) throw new PolicyException("Base URL is too long");
        if (text.chars().anyMatch(c -> c <= ' ' || c == '\\' || c == 0x7f)) throw new PolicyException("Base URL must not contain spaces, control characters or backslashes");

        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new PolicyException("Base URL is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new PolicyException("Base URL must use http or https");
        if (uri.isOpaque() || uri.getRawAuthority() == null) throw new PolicyException("Base URL must name a host");
        if (uri.getRawUserInfo() != null || uri.getRawAuthority().contains("@")) throw new PolicyException("Base URL must not contain user information");
        if (uri.getRawFragment() != null) throw new PolicyException("Base URL must not contain a fragment");
        if (uri.getRawQuery() != null) throw new PolicyException("Base URL must not contain a query string");

        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new PolicyException("Base URL must name a host");
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) throw new PolicyException("Base URL must use a host name, not an IP address");
        if (host.endsWith(".")) throw new PolicyException("Host name must not end with a dot");
        String[] labels = host.split("\\.", -1);
        if (IPV4_LIKE.matcher(host).matches() || NUMERIC_LABEL.matcher(labels[labels.length - 1]).matches()) {
            throw new PolicyException("Base URL must use a host name, not an IP address");
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) throw new PolicyException("Host name is not valid");
        }
        if (host.length() > 253) throw new PolicyException("Host name is not valid");
        if (METADATA_HOSTS.contains(host)) throw new PolicyException("Cloud metadata hosts are not allowed");

        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath().replaceAll("/{2,}", "/");
        for (String segment : path.split("/")) {
            String s = segment.toLowerCase(Locale.ROOT);
            if (s.equals(".") || s.equals("..") || s.contains("%2e") || s.contains("%2f") || s.contains("%5c")) {
                throw new PolicyException("Base URL path must not contain dot segments or encoded separators");
            }
        }
        return scheme + "://" + host + (defaultPort ? "" : ":" + port) + path;
    }

    /** scheme://host[:port] of a URL that {@link #normalize} accepted, as the allowed-host table stores it. */
    public static String origin(String normalized) {
        URI uri = URI.create(normalized);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }
}
