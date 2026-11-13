package qaengine.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The infrastructure key: exact hosts and ports from the deployment's file, and nothing broader. */
class ApprovedHostsTest {

    static final String META = "  approved-by=ops@example.com  change=CHG-1";

    @AfterEach void reset() {
        ExecutionLock.configure(ApprovedHosts.none());
    }

    @Test
    void anEntryApprovesOneExactHostOnOnePort() {
        ApprovedHosts h = ApprovedHosts.parse(List.of("# comment", "", "Staging.Example.com" + META + "  reason=Pilot of billing", "api.example.com:8443" + META));
        assertEquals(2, h.entries().size());
        assertEquals(new ApprovedHosts.Entry("staging.example.com", 443, "ops@example.com", "CHG-1", "Pilot of billing"), h.entries().get(0));
        assertTrue(h.allows("staging.example.com", 443));
        assertFalse(h.allows("staging.example.com", 80), "443 when no port is given, and only 443");
        assertFalse(h.allows("other.staging.example.com", 443), "never a subdomain");
        assertFalse(h.allows("example.com", 443), "never a parent");
        assertTrue(h.allows("api.example.com", 8443));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*.example.com", "example.*", "10.0.0.5", "[::1]", "localhost", "fixture.qa.internal", "https://staging.example.com",
            "staging.example.com/path", "staging", "staging.example.com:0", "staging.example.com:70000", "staging.example.com:x", "-bad.example.com"})
    void anythingBroaderOrOtherThanOneHostIsRefusedAndTheWholeFileWithIt(String host) {
        assertThrows(PolicyException.class, () -> ApprovedHosts.parse(List.of("good.example.com" + META, host + META)));
    }

    @Test
    void everyApprovalSaysWhoAndUnderWhichChange() {
        assertThrows(PolicyException.class, () -> ApprovedHosts.parse(List.of("staging.example.com")));
        assertThrows(PolicyException.class, () -> ApprovedHosts.parse(List.of("staging.example.com approved-by=ops@example.com")));
        assertThrows(PolicyException.class, () -> ApprovedHosts.parse(List.of("staging.example.com approved-by=a b change=c")), "one word");
        assertThrows(PolicyException.class, () -> ApprovedHosts.parse(List.of("staging.example.com" + META + " token=abc")), "no unknown keys");
        assertThrows(PolicyException.class, () -> ApprovedHosts.parse(List.of("a.example.com" + META, "A.example.com:443" + META)), "listed twice");
    }

    @Test
    void theFingerprintDependsOnlyOnTheHostsAndPorts() {
        String a = ApprovedHosts.parse(List.of("b.example.com" + META, "a.example.com:8443" + META)).fingerprint();
        String b = ApprovedHosts.parse(List.of("a.example.com:8443  approved-by=x  change=y", "b.example.com:443  approved-by=z  change=w")).fingerprint();
        assertEquals(a, b, "order and who approved do not change what is enforced");
        assertFalse(a.equals(ApprovedHosts.none().fingerprint()));
        // SHA-256 of the empty string: what the proxy's start script prints for an empty file.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ApprovedHosts.none().fingerprint());
    }

    @Test
    void theLockAllowsTheFixtureAlwaysAndARealHostOnlyWhenApproved() {
        assertTrue(ExecutionLock.isApproved("http://fixture.qa.internal:8090/"));
        assertFalse(ExecutionLock.isApproved("https://staging.example.com/"));
        assertThrows(SecurityException.class, () -> ExecutionLock.requireAllowed(TargetScope.of("https://staging.example.com")));

        ExecutionLock.configure(ApprovedHosts.parse(List.of("staging.example.com" + META)));
        assertTrue(ExecutionLock.isApproved("https://staging.example.com/app"));
        assertFalse(ExecutionLock.isApproved("http://staging.example.com/"), "http is port 80, not approved");
        assertFalse(ExecutionLock.isApproved("https://staging.example.com:8443/"));
        assertFalse(ExecutionLock.isApproved("https://evil.example.com/"));
        ExecutionLock.requireAllowed(TargetScope.of("https://staging.example.com"));
        assertThrows(SecurityException.class, () -> ExecutionLock.requireAllowed(TargetScope.of("https://staging.example.com", "https://sso.example.com")),
                "every origin of the scope must be approved");
        assertFalse(ExecutionLock.isLocal("https://staging.example.com/"), "an approved host is still not the fixture");
        assertThrows(SecurityException.class, () -> ExecutionLock.requireSecureForSecrets("http://staging.example.com/"), "no secret over plain HTTP to it");
    }
}
