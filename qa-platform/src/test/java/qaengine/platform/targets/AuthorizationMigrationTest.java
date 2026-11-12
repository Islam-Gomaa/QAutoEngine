package qaengine.platform.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Predicate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import qaengine.platform.targets.Eligibility.Reason;

/**
 * V14 and V15 run against data written under the versions before them, as an upgrade would:
 * the old free-text field is cleared, and an authorization recorded before expiry existed is kept but counts as expired.
 */
class AuthorizationMigrationTest {

    record Row(String reference, String by, OffsetDateTime at, OffsetDateTime expiresAt) {}

    static Row row(JdbcClient db, UUID environment) {
        return db.sql("SELECT authorization_reference, authorized_by, authorized_at, authorization_expires_at FROM environment WHERE id = ?").param(environment)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class))).single();
    }

    @Test
    void existingAuthorizationsArePreservedAndTreatedAsExpired() {
        try (var postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcClient db = JdbcClient.create(source);

            // ---- under V13: the create-time field could set authorized_by and authorized_at as free text
            Flyway.configure().dataSource(source).target("13").load().migrate();
            UUID org = UUID.randomUUID(), project = UUID.randomUUID(), target = UUID.randomUUID();
            UUID freeText = UUID.randomUUID(), untouchedOld = UUID.randomUUID();
            db.sql("INSERT INTO organization (id, name) VALUES (?, 'o')").param(org).update();
            db.sql("INSERT INTO project (organization_id, id, name) VALUES (?,?, 'p')").params(org, project).update();
            db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?, 't')").params(org, target, project).update();
            db.sql("""
                    INSERT INTO environment (organization_id, id, target_id, name, classification, base_url, authorized_by, authorized_at)
                    VALUES (?,?,?, 'old', 'QA', 'https://shop.example/', 'someone@x.test', now() - interval '5 days')
                    """).params(org, freeText, target).update();
            db.sql("INSERT INTO environment (organization_id, id, target_id, name, classification, base_url) VALUES (?,?,?, 'none', 'QA', 'https://shop.example/')")
                    .params(org, untouchedOld, target).update();

            // ---- V14: unaudited values are cleared, everything else is untouched
            Flyway.configure().dataSource(source).target("14").load().migrate();
            // The expiry column does not exist yet at V14.
            String v14 = "SELECT authorization_reference IS NULL AND authorized_by IS NULL AND authorized_at IS NULL FROM environment WHERE id = ?";
            assertTrue(db.sql(v14).param(freeText).query(Boolean.class).single(), "free text from the old create field cannot count as authorization");
            assertTrue(db.sql(v14).param(untouchedOld).query(Boolean.class).single());
            assertEquals("old", db.sql("SELECT name FROM environment WHERE id = ?").param(freeText).query(String.class).single(), "the environment itself is kept");

            // ---- under V14: an authorization recorded through the API, before expiry existed
            UUID recorded = UUID.randomUUID(), unauthorized = UUID.randomUUID(), fixture = UUID.randomUUID();
            OffsetDateTime recordedAt = db.sql("SELECT now() - interval '3 days'").query(OffsetDateTime.class).single();
            db.sql("""
                    INSERT INTO environment (organization_id, id, target_id, name, classification, base_url, authorization_reference, authorized_by, authorized_at)
                    VALUES (?,?,?, 'recorded', 'QA', 'https://jeddah-lpr.wakeb.dev/', 'WAKEB-AUTH-2026-014', 'admin@x.test', ?)
                    """).params(org, recorded, target, recordedAt).update();
            db.sql("INSERT INTO environment (organization_id, id, target_id, name, classification, base_url) VALUES (?,?,?, 'unauthorized', 'QA', 'https://other.example/')")
                    .params(org, unauthorized, target).update();
            db.sql("INSERT INTO environment (organization_id, id, target_id, name, classification, base_url) VALUES (?,?,?, 'fixture', 'LOCAL', 'http://fixture.qa.internal:8090/')")
                    .params(org, fixture, target).update();

            // ---- V15
            Flyway.configure().dataSource(source).target("15").load().migrate();
            assertEquals("15", db.sql("SELECT max(version::int)::text FROM flyway_schema_history WHERE success").query(String.class).single());

            Row r = row(db, recorded);
            assertEquals("WAKEB-AUTH-2026-014", r.reference(), "the record is preserved");
            assertEquals("admin@x.test", r.by());
            assertEquals(recordedAt.toInstant(), r.at().toInstant(), "when it was recorded is unchanged");
            assertNotNull(r.expiresAt());
            assertEquals(r.at().toInstant(), r.expiresAt().toInstant(), "it expires when it was recorded: already over");

            Instant now = Instant.now();
            assertFalse(r.expiresAt().toInstant().isAfter(now));
            // As the platform decides it today: the gate comes first for any real host.
            assertEquals(Reason.HOST_NOT_APPROVED, Eligibility.of("https://jeddah-lpr.wakeb.dev/", r.reference(), r.expiresAt(), now).lockReason());
            // And as it would once the host is approved: expired, so it must be recorded again with its window.
            Predicate<String> approved = url -> url.equals("https://jeddah-lpr.wakeb.dev/");
            assertEquals(Reason.AUTHORIZATION_EXPIRED, Eligibility.of("https://jeddah-lpr.wakeb.dev/", r.reference(), r.expiresAt(), now, approved).lockReason());

            for (UUID env : new UUID[] {unauthorized, fixture, freeText, untouchedOld}) {
                assertEquals(new Row(null, null, null, null), row(db, env), "nothing is invented for an environment that had no authorization");
            }
            assertTrue(Eligibility.of("http://fixture.qa.internal:8090/", null, null, now).executable(), "the fixture still runs");
            assertEquals(Reason.NOT_AUTHORIZED, Eligibility.of("https://other.example/", null, null, now, url -> true).lockReason());

            // The constraints now hold for anything written after the upgrade.
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.sql(
                    "UPDATE environment SET authorization_reference = 'REF-1', authorized_by = 'x', authorized_at = now() WHERE id = ?").param(unauthorized).update(),
                    "a reference without a window");
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.sql(
                    "UPDATE environment SET authorization_expires_at = NULL WHERE id = ?").param(recorded).update(),
                    "a window cannot be dropped from a recorded authorization");
        }
    }
}
