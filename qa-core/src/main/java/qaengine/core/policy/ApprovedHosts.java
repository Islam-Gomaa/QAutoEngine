package qaengine.core.policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The real hosts an operator has approved for this deployment: the infrastructure key. Read from one file that the
 * platform, the worker and the egress proxy all mount read-only, so the three enforce the same list. It is never
 * changed from the product: approving a host is a deployment change, separate from the written authorization an
 * Organization Admin records for each environment, and both are needed before anything runs.
 *
 * <p>One entry per line: an exact host name, optionally with a port (443 when none is given), then who approved it
 * and the change that did. No wildcards, no IP addresses, no schemes or paths:
 * <pre>
 *   staging.example.com:443  approved-by=ops@example.com  change=CHG-1042  reason=Pilot of the billing portal
 * </pre>
 * Lines starting with # are comments. One invalid line refuses the whole file, so a mistake fails closed.
 */
public record ApprovedHosts(List<Entry> entries, String fingerprint) {

    /** @param reason free text, may be empty */
    public record Entry(String host, int port, String approvedBy, String change, String reason) {
        public String hostPort() {
            return host + ":" + port;
        }
    }

    /** A real DNS name: lower-case labels, at least one dot, no wildcard, not an address. */
    private static final Pattern HOST = Pattern.compile("(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9@._+:/#-]{1,120}");
    private static final Set<String> RESERVED = Set.of("localhost", ExecutionLock.FIXTURE_HOST);

    public ApprovedHosts {
        entries = List.copyOf(entries);
    }

    public static ApprovedHosts none() {
        return of(List.of());
    }

    static ApprovedHosts of(List<Entry> entries) {
        return new ApprovedHosts(entries, fingerprint(entries));
    }

    /**
     * The same hosts give the same fingerprint wherever they are read: sorted "host:port" lines, SHA-256. The proxy's
     * start script computes it the same way, so the three components can be compared.
     */
    static String fingerprint(List<Entry> entries) {
        TreeSet<String> lines = new TreeSet<>();
        entries.forEach(e -> lines.add(e.hostPort()));
        StringBuilder b = new StringBuilder();
        lines.forEach(l -> b.append(l).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A missing file means no real host is approved: only the local fixture runs. */
    public static ApprovedHosts load(Path file) {
        if (file == null || !Files.exists(file)) return none();
        if (Files.isDirectory(file)) throw new PolicyException("The approved-hosts path is a directory: create the file (see deploy/approved-hosts.example)");
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PolicyException("The approved-hosts file could not be read");
        }
    }

    /** @throws PolicyException naming the line, never repeating secrets: the file holds none */
    public static ApprovedHosts parse(List<String> lines) {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int n = i + 1;
            String[] parts = line.split("\\s+", 2);
            String hostPort = parts[0].toLowerCase(Locale.ROOT);
            String host = hostPort;
            int port = 443;
            int colon = hostPort.lastIndexOf(':');
            if (colon >= 0) {
                host = hostPort.substring(0, colon);
                try {
                    port = Integer.parseInt(hostPort.substring(colon + 1));
                } catch (NumberFormatException e) {
                    throw new PolicyException("Approved hosts, line " + n + ": the port is not a number");
                }
            }
            if (port < 1 || port > 65535) throw new PolicyException("Approved hosts, line " + n + ": the port is out of range");
            if (host.contains("*")) throw new PolicyException("Approved hosts, line " + n + ": wildcards are not allowed; approve each host");
            if (host.matches("[0-9.]+") || host.contains("[")) throw new PolicyException("Approved hosts, line " + n + ": approve a host name, not an address");
            if (!HOST.matcher(host).matches()) throw new PolicyException("Approved hosts, line " + n + ": not a host name, such as staging.example.com");
            if (RESERVED.contains(host)) throw new PolicyException("Approved hosts, line " + n + ": that host is the built-in fixture, not a real target");
            Map<String, String> meta = metadata(n, parts.length > 1 ? parts[1] : "");
            String by = meta.get("approved-by"), change = meta.get("change");
            if (by == null || change == null) throw new PolicyException("Approved hosts, line " + n + ": say who approved it (approved-by=) and the change that did (change=)");
            Entry e = new Entry(host, port, by, change, meta.getOrDefault("reason", ""));
            if (out.putIfAbsent(e.hostPort(), e) != null) throw new PolicyException("Approved hosts, line " + n + ": " + e.hostPort() + " is listed twice");
        }
        return of(new ArrayList<>(out.values()));
    }

    private static Map<String, String> metadata(int n, String rest) {
        Map<String, String> meta = new LinkedHashMap<>();
        String text = rest.strip();
        while (!text.isEmpty()) {
            int eq = text.indexOf('=');
            if (eq <= 0) throw new PolicyException("Approved hosts, line " + n + ": expected key=value after the host");
            String key = text.substring(0, eq);
            String value;
            if (key.equals("reason")) {
                value = text.substring(eq + 1).strip();
                text = "";
                if (value.length() > 200 || value.chars().anyMatch(Character::isISOControl)) throw new PolicyException("Approved hosts, line " + n + ": the reason is too long");
            } else {
                String[] split = text.substring(eq + 1).split("\\s+", 2);
                value = split[0];
                text = split.length > 1 ? split[1].strip() : "";
                if (!TOKEN.matcher(value).matches()) throw new PolicyException("Approved hosts, line " + n + ": " + key + " must be one word");
            }
            if (!Set.of("approved-by", "change", "reason").contains(key)) throw new PolicyException("Approved hosts, line " + n + ": unknown key " + key);
            meta.put(key, value);
        }
        return meta;
    }

    public boolean allows(String host, int port) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return entries.stream().anyMatch(e -> e.host().equals(h) && e.port() == port);
    }
}
