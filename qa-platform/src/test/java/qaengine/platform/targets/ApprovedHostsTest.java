package qaengine.platform.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qaengine.core.policy.ApprovedHosts;
import qaengine.core.policy.ExecutionLock;

/**
 * The approved-hosts file is enforced by three readers: the platform and the worker through {@link ApprovedHosts},
 * and the egress proxy through its start script. They must agree exactly, on what they accept, what they refuse and
 * the fingerprint they print. The fixture stays built in, and no real host is ever committed to the repository.
 */
class ApprovedHostsTest {

    static final Path EGRESS = Path.of("..", "deploy", "egress");
    static final Path SCRIPT = EGRESS.resolve("qa-entrypoint.sh");

    static Set<String> lines(Path file) throws IOException {
        return Files.readAllLines(file).stream().map(String::strip).filter(s -> !s.isEmpty() && !s.startsWith("#")).collect(Collectors.toCollection(TreeSet::new));
    }

    record Proxy(int exit, String fingerprint, String acls, String rules, String error) {}

    /** Runs the proxy's own start script, without starting Squid, on the given file. */
    static Proxy proxy(List<String> file) throws Exception {
        Path dir = Files.createTempDirectory("egress");
        Path src = dir.resolve("approved-hosts.txt");
        Files.write(src, file);
        ProcessBuilder pb = new ProcessBuilder("bash", SCRIPT.toString());
        pb.environment().put("QA_APPROVED_HOSTS_SRC", src.toString());
        pb.environment().put("QA_PROXY_OUT", dir.toString());
        pb.environment().put("QA_GENERATE_ONLY", "1");
        Process p = pb.start();
        String err = new String(p.getErrorStream().readAllBytes());
        int exit = p.waitFor();
        Path fp = dir.resolve("qa-approved-fingerprint");
        return new Proxy(exit, Files.exists(fp) ? Files.readString(fp).strip() : "", Files.exists(dir.resolve("qa-approved-acls.conf")) ? Files.readString(dir.resolve("qa-approved-acls.conf")) : "",
                Files.exists(dir.resolve("qa-approved-rules.conf")) ? Files.readString(dir.resolve("qa-approved-rules.conf")) : "", err);
    }

    static final String META = "  approved-by=ops@example.com  change=CHG-7";

    @Test
    void theProxyAndThePlatformReadTheSameFileTheSameWay() throws Exception {
        List<String> file = List.of("# approved", "Staging.Example.com" + META + "  reason=Pilot", "api.example.com:8443" + META);
        Proxy proxy = proxy(file);
        assertEquals(0, proxy.exit(), proxy.error());
        assertEquals(ApprovedHosts.parse(file).fingerprint(), proxy.fingerprint(), "the same fingerprint, so a mismatch shows");
        assertTrue(proxy.acls().contains("acl qa_host_1 dstdomain -n staging.example.com\nacl qa_port_1 port 443\nacl allowed_hosts dstdomain -n staging.example.com"));
        assertTrue(proxy.acls().contains("acl qa_host_2 dstdomain -n api.example.com\nacl qa_port_2 port 8443"));
        assertEquals("http_access allow qa_host_1 qa_port_1\nhttp_access allow qa_host_2 qa_port_2\n", proxy.rules(), "each host only on its own port");
        Proxy empty = proxy(List.of("# nothing approved"));
        assertEquals(ApprovedHosts.none().fingerprint(), empty.fingerprint());
        assertEquals("", empty.rules());
    }

    @ParameterizedTest
    @ValueSource(strings = {"*.example.com", "10.0.0.5", "localhost", "fixture.qa.internal", "staging", "staging.example.com:70000", "https://staging.example.com"})
    void whatTheParserRefusesTheProxyRefusesToStartWith(String host) throws Exception {
        List<String> file = List.of("good.example.com" + META, host + META);
        boolean javaRefuses;
        try {
            ApprovedHosts.parse(file);
            javaRefuses = false;
        } catch (qaengine.core.policy.PolicyException e) {
            javaRefuses = true;
        }
        assertTrue(javaRefuses);
        Proxy proxy = proxy(file);
        assertNotEquals(0, proxy.exit(), "the proxy fails closed on " + host);
        assertEquals("", proxy.rules().contains("good.example.com") ? "partial" : "", "and opens nothing");
    }

    @Test
    void theProxyRefusesEverythingItWasNotToldAndInTheRightOrder() throws IOException {
        String conf = Files.readString(EGRESS.resolve("squid.conf"));
        int ip = conf.indexOf("http_access deny ip_literal"), names = conf.indexOf("http_access deny !allowed_hosts"),
                priv = conf.indexOf("http_access deny to_private !private_approved"), fixture = conf.indexOf("http_access allow fixture_hosts fixture_ports"),
                real = conf.indexOf("include /tmp/qa-approved-rules.conf"), all = conf.indexOf("http_access deny all");
        assertTrue(ip >= 0 && ip < names && names < priv && priv < fixture && fixture < real && real < all, "addresses, then unknown names before DNS, then private addresses, then allows, then nothing");
        assertTrue(conf.indexOf("include /tmp/qa-approved-acls.conf") < ip);
    }

    @Test
    void theFixtureStaysBuiltInAndNoRealHostIsCommitted() throws Exception {
        assertEquals(Set.of(ExecutionLock.FIXTURE_HOST, "rebind.qa.internal"), lines(EGRESS.resolve("allowed-hosts.txt")), "the proxy's built-in list is the fixture");
        assertFalse(ExecutionLock.isApproved("http://rebind.qa.internal/"), "allowed at the proxy for the rebinding test only; the lock refuses it");
        assertEquals(Set.of(ExecutionLock.FIXTURE_HOST), lines(EGRESS.resolve("private-approved.txt")));
        for (String host : Eligibility.FIXTURE_HOSTS) {
            String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host; // an IPv6 address needs brackets in a URL
            assertTrue(ExecutionLock.isLocal("http://" + authority + "/"), host);
        }
        assertEquals(List.of(), ApprovedHosts.load(Path.of("..", "deploy", "approved-hosts.example")).entries(), "the committed example approves nothing");
        String ignored = Files.readString(Path.of("..", ".gitignore"));
        assertTrue(ignored.lines().anyMatch(l -> l.strip().equals("deploy/approved-hosts.txt")), "the deployment's real list is never committed");
    }
}
