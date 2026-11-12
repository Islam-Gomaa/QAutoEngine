package qaengine.platform.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qaengine.platform.targets.Eligibility.Reason;

class EligibilityTest {

    static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    static final OffsetDateTime LATER = OffsetDateTime.of(2027, 1, 31, 0, 0, 0, 0, ZoneOffset.UTC);
    static final OffsetDateTime EARLIER = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    static final String REAL = "https://jeddah-lpr.wakeb.dev/";

    /** What ExecutionLock would answer if REAL were one day approved there. Only the test can say so. */
    static final Predicate<String> APPROVED_REAL = url -> url.equals(REAL) || qaengine.core.policy.ExecutionLock.isLocal(url);

    @ParameterizedTest
    @ValueSource(strings = {"http://fixture.qa.internal:8090/", "http://127.0.0.1:8090/", "http://localhost/", "http://[::1]:9/"})
    void theFixtureRunsWithoutAuthorization(String url) {
        var r = Eligibility.of(url, null, null, NOW);
        assertTrue(r.executable());
        assertEquals(Reason.FIXTURE, r.reason());
        assertNull(r.lockReason());
    }

    @Test
    void aRealTargetIsLockedByTheGateWhateverItsAuthorization() {
        assertEquals(Reason.HOST_NOT_APPROVED, Eligibility.of(REAL, null, null, NOW).lockReason());
        var authorized = Eligibility.of(REAL, "WAKEB-AUTH-1", LATER, NOW);
        assertFalse(authorized.executable(), "authorization is never a key on its own");
        assertEquals(Reason.HOST_NOT_APPROVED, authorized.lockReason());
    }

    @Test
    void lookAlikeHostsAreNotTheFixture() {
        for (String url : new String[] {"http://fixture.qa.internal.evil.test/", "http://evilfixture.qa.internal/", "http://localhost.evil.test/", "http://127.0.0.1.nip.io/"}) {
            assertFalse(Eligibility.isFixture(url), url);
            assertEquals(Reason.HOST_NOT_APPROVED, Eligibility.of(url, null, null, NOW).lockReason(), url);
        }
    }

    @Test
    void anApprovedRealHostStillNeedsAnAuthorizationInDate() {
        assertFalse(Eligibility.isFixture(REAL));
        assertEquals(Reason.NOT_AUTHORIZED, Eligibility.of(REAL, null, null, NOW, APPROVED_REAL).lockReason(), "approval in code is not enough");
        assertEquals(Reason.NOT_AUTHORIZED, Eligibility.of(REAL, "WAKEB-AUTH-1", null, NOW, APPROVED_REAL).lockReason(), "nor is a reference without a window");
        assertEquals(Reason.AUTHORIZATION_EXPIRED, Eligibility.of(REAL, "WAKEB-AUTH-1", EARLIER, NOW, APPROVED_REAL).lockReason());
        assertEquals(Reason.AUTHORIZATION_EXPIRED, Eligibility.of(REAL, "WAKEB-AUTH-1", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), NOW, APPROVED_REAL).lockReason(),
                "it ends at its expiry, not after");
        var eligible = Eligibility.of(REAL, "WAKEB-AUTH-1", LATER, NOW, APPROVED_REAL);
        assertTrue(eligible.executable());
        assertEquals(Reason.ELIGIBLE, eligible.reason());
    }

    @Test
    void refusalsKeepTheGateWording() {
        assertEquals("Real targets are locked until the pre-pilot security gate passes", Eligibility.refusal(Reason.HOST_NOT_APPROVED));
        assertEquals("This environment has no written authorization", Eligibility.refusal(Reason.NOT_AUTHORIZED));
        assertEquals("This environment's authorization has expired", Eligibility.refusal(Reason.AUTHORIZATION_EXPIRED));
        assertThrows(IllegalArgumentException.class, () -> Eligibility.refusal(Reason.FIXTURE));
        assertThrows(IllegalArgumentException.class, () -> Eligibility.refusal(Reason.ELIGIBLE));
    }
}
