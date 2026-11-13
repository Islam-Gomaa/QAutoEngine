package qaengine.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V8 moves existing logins into sign-in profiles. This runs it against data written under V7, as an upgrade would. */
class CredentialMigrationTest {

    @Test
    void existingLoginsBecomeDefaultProfilesAndSharedCredentialsAreMarked() {
        try (var postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).target("7").load().migrate();
            JdbcClient db = JdbcClient.create(source);

            UUID org = UUID.randomUUID(), project = UUID.randomUUID(), target = UUID.randomUUID(), user = UUID.randomUUID();
            UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), none = UUID.randomUUID();
            UUID shared = UUID.randomUUID(), single = UUID.randomUUID(), unused = UUID.randomUUID();
            UUID openRun = UUID.randomUUID(), finishedRun = UUID.randomUUID();
            db.sql("INSERT INTO organization (id, name) VALUES (?, 'o')").param(org).update();
            db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?, 'u@x.test', 'x')").param(user).update();
            db.sql("INSERT INTO project (organization_id, id, name) VALUES (?,?, 'p')").params(org, project).update();
            db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?, 't')").params(org, target, project).update();
            for (UUID cred : List.of(shared, single, unused)) {
                db.sql("INSERT INTO credential (organization_id, id, project_id, name, wrapped_key, ciphertext, created_at) VALUES (?,?,?,?, '\\x00', '\\x00', now() - interval '3 days')")
                        .params(org, cred, project, cred.toString()).update();
            }
            Map<UUID, UUID> logins = Map.of(a, shared, b, shared, c, single);
            for (UUID env : List.of(a, b, c, none)) {
                UUID cred = logins.get(env);
                db.sql("""
                        INSERT INTO environment (organization_id, id, target_id, name, classification, base_url, login_method, login_credential_id, login_path, login_submit_path, login_success_path)
                        VALUES (?,?,?,?, 'QA', 'https://shop.example/', ?, ?, ?, ?, ?)
                        """).params(org, env, target, env.toString(), cred == null ? null : "PASSWORD", cred, cred == null ? null : "/login",
                        cred == null ? null : "/session", cred == null ? null : "/account").update();
            }
            db.sql("INSERT INTO test_run (organization_id, id, project_id, environment_id, seed, idempotency_key, created_by) VALUES (?,?,?,?, 1, 'open', ?)").params(org, openRun, project, c, user).update();
            db.sql("INSERT INTO test_run (organization_id, id, project_id, environment_id, seed, idempotency_key, created_by, finished_at, status) VALUES (?,?,?,?, 1, 'done', ?, now(), 'SUCCEEDED')")
                    .params(org, finishedRun, project, c, user).update();

            Flyway.configure().dataSource(source).target("8").load().migrate();

            record Cred(UUID environment, boolean shared, boolean enabled, boolean updatedIsCreated) {}
            var creds = new java.util.HashMap<UUID, Cred>();
            db.sql("SELECT id, environment_id, shared, enabled, updated_at = created_at FROM credential").query((rs, n) -> creds.put(rs.getObject(1, UUID.class),
                    new Cred(rs.getObject(2, UUID.class), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5)))).list();
            assertTrue(creds.get(shared).shared(), "used by two environments: kept, and marked shared");
            assertNull(creds.get(shared).environment());
            assertEquals(c, creds.get(single).environment(), "used by one environment: bound to it");
            assertFalse(creds.get(single).shared());
            assertNull(creds.get(unused).environment(), "unused: bound on first use");
            assertFalse(creds.get(unused).shared());
            assertTrue(creds.values().stream().allMatch(x -> x.enabled() && x.updatedIsCreated()));

            record Profile(UUID environment, UUID credential, String name, String method, boolean isDefault, boolean enabled, String login, String submit, String success) {}
            List<Profile> profiles = db.sql("SELECT environment_id, credential_id, name, method, is_default, enabled, login_path, submit_path, success_path FROM auth_profile ORDER BY environment_id")
                    .query((rs, n) -> new Profile(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6),
                            rs.getString(7), rs.getString(8), rs.getString(9))).list();
            assertEquals(3, profiles.size(), "one profile for each environment that had a login, none for the one that did not");
            for (Profile p : profiles) {
                assertEquals(logins.get(p.environment()), p.credential());
                assertEquals(new Profile(p.environment(), p.credential(), "Default", "PASSWORD", true, true, "/login", "/session", "/account"), p);
            }

            UUID profileOfC = db.sql("SELECT id FROM auth_profile WHERE environment_id = ?").param(c).query(UUID.class).single();
            assertEquals(profileOfC, db.sql("SELECT auth_profile_id FROM test_run WHERE id = ?").param(openRun).query(UUID.class).single(), "a queued run keeps signing in");
            assertTrue(db.sql("SELECT auth_profile_id IS NULL FROM test_run WHERE id = ?").param(finishedRun).query(Boolean.class).single(), "history is not rewritten");
            assertEquals("8", db.sql("SELECT max(version::int)::text FROM flyway_schema_history WHERE success").query(String.class).single());
        }
    }
}
