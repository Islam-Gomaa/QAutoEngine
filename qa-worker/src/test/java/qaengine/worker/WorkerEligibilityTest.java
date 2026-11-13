package qaengine.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import qaengine.contracts.WorkerContracts.JobAssignment;
import qaengine.contracts.WorkerContracts.RunLimits;
import qaengine.core.policy.ApprovedHosts;
import qaengine.core.policy.ExecutionLock;

/**
 * The worker decides again, with what it holds itself, whatever the platform said: its own approved-hosts file, and
 * the authorization's expiry. A job the platform should never have sent is refused before anything is opened.
 */
class WorkerEligibilityTest {

    static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    @AfterEach void reset() {
        ExecutionLock.configure(ApprovedHosts.none());
    }

    static JobAssignment job(String startUrl, List<String> origins, String expires, String mode) {
        return new JobAssignment(UUID.randomUUID(), UUID.randomUUID(), 1, "t", startUrl, 1, mode, "STAGING", origins, List.of(), List.of(),
                new RunLimits(10, 10, 3, 5, 2), List.of(), null, "WEB", null, null, "EXPLORATION", List.of(), expires);
    }

    @Test
    void theFixtureNeedsNothingElse() {
        assertNull(WorkerMain.refusal(job("http://fixture.qa.internal:8090/", List.of("http://fixture.qa.internal:8090"), null, "READ_ONLY"), NOW));
    }

    @Test
    void aRealTargetNeedsTheHostInThisWorkersFileAndAnAuthorizationInDate() {
        String real = "https://staging.realapp.example/";
        List<String> origins = List.of("https://staging.realapp.example");
        assertEquals("HOST_NOT_APPROVED", WorkerMain.refusal(job(real, origins, "2027-01-01T00:00:00Z", "READ_ONLY"), NOW), "the platform said yes; this worker's file says no");

        ExecutionLock.configure(ApprovedHosts.parse(List.of("staging.realapp.example  approved-by=ops  change=CHG-1")));
        assertEquals("NOT_AUTHORIZED", WorkerMain.refusal(job(real, origins, null, "READ_ONLY"), NOW));
        assertEquals("AUTHORIZATION_EXPIRED", WorkerMain.refusal(job(real, origins, "2026-11-14T11:59:59Z", "READ_ONLY"), NOW));
        assertEquals("MUTATION_NOT_AVAILABLE", WorkerMain.refusal(job(real, origins, "2027-01-01T00:00:00Z", "AUTHORIZED_MUTATION"), NOW));
        assertNull(WorkerMain.refusal(job(real, origins, "2027-01-01T00:00:00Z", "READ_ONLY"), NOW));
        assertEquals("HOST_NOT_APPROVED", WorkerMain.refusal(job(real, List.of("https://staging.realapp.example", "https://sso.realapp.example"), "2027-01-01T00:00:00Z", "READ_ONLY"), NOW),
                "every origin the run may reach must be approved, not just the start");
    }
}
