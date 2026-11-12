package qaengine.platform.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import qaengine.core.policy.ExecutionLock;

/**
 * Hosts are approved in two places, reviewed together: {@link ExecutionLock} in code and the egress proxy's allowlist.
 * This keeps them from drifting apart, and fails loudly the day a real host is approved, so that change cannot pass unnoticed.
 */
class ApprovedHostsTest {

    static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");
    /** Allowed at the proxy only, for the DNS-rebinding attack test. ExecutionLock refuses it, so no run can use it. */
    static final Set<String> PROXY_TEST_ONLY = Set.of("rebind.qa.internal");

    @SuppressWarnings("unchecked")
    static Set<String> lockAllowed() throws ReflectiveOperationException {
        var field = ExecutionLock.class.getDeclaredField("ALLOWED");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    static Set<String> egress(String file) throws IOException {
        // Tests run from the module directory.
        return Files.readAllLines(Path.of("..", "deploy", "egress", file)).stream().map(String::strip)
                .filter(s -> !s.isEmpty() && !s.startsWith("#")).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyHostTheLockAllowsIsAllowedAtTheProxy() throws Exception {
        Set<String> named = new TreeSet<>(lockAllowed());
        named.removeAll(LOOPBACK);
        assertTrue(egress("allowed-hosts.txt").containsAll(named), "ExecutionLock allows " + named + " but the proxy does not");
    }

    @Test
    void theProxyAllowsNothingTheLockRefuses() throws Exception {
        Set<String> extra = egress("allowed-hosts.txt");
        extra.removeAll(lockAllowed());
        extra.removeAll(PROXY_TEST_ONLY);
        assertEquals(Set.of(), extra, "the proxy allows hosts that ExecutionLock refuses");
        for (String host : PROXY_TEST_ONLY) assertTrue(!ExecutionLock.isLocal("http://" + host + "/"), host + " must stay refused by ExecutionLock");
    }

    @Test
    void privateAddressesAreApprovedOnlyForTheFixture() throws Exception {
        assertEquals(Set.of(ExecutionLock.FIXTURE_HOST), egress("private-approved.txt"));
    }

    @Test
    void theFixtureListIsWhatTheLockAllows() throws Exception {
        assertTrue(lockAllowed().containsAll(Eligibility.FIXTURE_HOSTS), "a fixture host the lock refuses would never run");
    }

    /**
     * The pre-pilot gate. Approving a real host is a reviewed code change made only after the gate passes
     * (docs/PILOT-READINESS.md). When that day comes, this test changes in the same reviewed commit.
     */
    @Test
    void noRealHostIsApprovedBeforeThePrePilotGate() throws Exception {
        assertEquals(Eligibility.FIXTURE_HOSTS, lockAllowed(), "a real host is approved in ExecutionLock");
        assertEquals(Set.of(ExecutionLock.FIXTURE_HOST, "rebind.qa.internal"), egress("allowed-hosts.txt"), "a real host is approved at the egress proxy");
    }
}
