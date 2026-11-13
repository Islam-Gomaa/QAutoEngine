package qaengine.platform.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import qaengine.core.policy.ApprovedHosts;
import qaengine.core.policy.ExecutionLock;
import qaengine.platform.IntegrationTestBase;
import qaengine.platform.shared.Principal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A real application, registered by configuration alone, through both keys: an operator approves its host in the
 * deployment's file, and an Organization Admin records its written authorization. Either missing, expired or revoked
 * refuses it, at run creation, when a worker claims the job, on every heartbeat, and before a credential leaves.
 * Nothing here contacts any host: a run that passes eligibility is only queued.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"qa.worker-key=test-worker-key-0123456789abcdef0123456789", "qa.master-key-file=${java.io.tmpdir}/qa-test-master.key"})
class RealTargetEligibilityTest extends IntegrationTestBase {

    static final String KEY = "test-worker-key-0123456789abcdef0123456789";
    static final String REAL = "https://staging.realapp.example/";
    static final ApprovedHosts APPROVED = ApprovedHosts.parse(List.of("staging.realapp.example  approved-by=ops@example.com  change=CHG-1  reason=Pilot"));

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    final ObjectMapper json = new ObjectMapper();
    UUID org, project;
    Principal admin;

    @BeforeEach
    void seed() throws Exception {
        Path key = Path.of(System.getProperty("java.io.tmpdir"), "qa-test-master.key");
        if (!Files.exists(key)) Files.writeString(key, java.util.Base64.getEncoder().encodeToString(new byte[32]));
        db.sql("TRUNCATE organization, app_user CASCADE").update();
        org = UUID.randomUUID();
        UUID u = UUID.randomUUID();
        db.sql("INSERT INTO organization (id, name) VALUES (?, 'o')").param(org).update();
        db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?, 'a@x.test', 'x')").param(u).update();
        db.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (?,?, 'ORGANIZATION_ADMIN')").params(org, u).update();
        admin = new Principal(u, org, "a@x.test", true);
        project = UUID.fromString(ok(post("/api/v1/projects"), Map.of("name", "Real " + UUID.randomUUID())).get("id").asString());
        ExecutionLock.configure(APPROVED);
    }

    @AfterEach void reset() {
        ExecutionLock.configure(ApprovedHosts.none());
    }

    MockHttpServletResponse call(MockHttpServletRequestBuilder r, Object body) throws Exception {
        r.with(authentication(new UsernamePasswordAuthenticationToken(admin, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))))).with(csrf());
        if (body != null) r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(r).andReturn().getResponse();
    }

    JsonNode ok(MockHttpServletRequestBuilder r, Object body) throws Exception {
        var res = call(r, body);
        String text = res.getContentAsString();
        assertTrue(res.getStatus() < 300, () -> res.getStatus() + " " + text);
        return text.isEmpty() ? null : json.readTree(text);
    }

    UUID environment(String url, String classification) throws Exception {
        String target = ok(post("/api/v1/projects/" + project + "/targets"), Map.of("name", "App " + UUID.randomUUID(), "type", "WEB")).get("id").asString();
        return UUID.fromString(ok(post("/api/v1/projects/" + project + "/targets/" + target + "/environments"),
                Map.of("name", "Staging", "classification", classification, "baseUrl", url)).get("id").asString());
    }

    JsonNode listed(UUID env) throws Exception {
        for (JsonNode e : ok(get("/api/v1/projects/" + project + "/environments"), null)) if (e.get("id").asString().equals(env.toString())) return e;
        throw new AssertionError();
    }

    MockHttpServletResponse run(UUID env) throws Exception {
        return call(post("/api/v1/projects/" + project + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), Map.of("environmentId", env.toString()));
    }

    String detail(MockHttpServletResponse r) throws Exception {
        return json.readTree(r.getContentAsString()).get("detail").asString();
    }

    void authorize(UUID env, Instant until) throws Exception {
        ok(put("/api/v1/projects/" + project + "/environments/" + env + "/authorization"), Map.of("reference", "WA-2026-01", "expiresAt", until.truncatedTo(ChronoUnit.SECONDS).toString()));
    }

    MockHttpServletResponse claim(String fingerprint) throws Exception {
        String body = fingerprint == null ? "{\"workerId\":\"w\"}" : "{\"workerId\":\"w\",\"approvedHosts\":\"" + fingerprint + "\"}";
        return mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    @Test
    void anApprovedHostWithoutAWrittenAuthorizationStaysLocked() throws Exception {
        UUID env = environment(REAL, "STAGING");
        assertEquals("NOT_AUTHORIZED", listed(env).get("lockReason").asString());
        var r = run(env);
        assertEquals(409, r.getStatus());
        assertEquals("This environment has no written authorization", detail(r));
    }

    @Test
    void bothKeysTogetherPassBackendEligibilityAndTheJobCarriesTheAuthorization() throws Exception {
        UUID env = environment(REAL, "STAGING");
        Instant until = Instant.now().plus(Duration.ofDays(30));
        authorize(env, until);
        JsonNode e = listed(env);
        assertTrue(e.get("executable").asBoolean(), e.toString());
        assertTrue(e.get("lockReason").isNull());
        assertEquals(201, run(env).getStatus(), "eligible: queued, and nothing has contacted the host");

        var res = claim(APPROVED.fingerprint());
        assertEquals(200, res.getStatus());
        JsonNode job = json.readTree(res.getContentAsString());
        assertEquals(REAL, job.get("startUrl").asString());
        assertEquals(until.truncatedTo(ChronoUnit.SECONDS), Instant.parse(job.get("authorizationExpiresAt").asString()), "the worker checks it again itself");
    }

    @Test
    void aWorkerWithADifferentApprovedHostsFileGetsNoJob() throws Exception {
        UUID env = environment(REAL, "STAGING");
        authorize(env, Instant.now().plus(Duration.ofDays(30)));
        assertEquals(201, run(env).getStatus());
        assertEquals(412, claim(ApprovedHosts.none().fingerprint()).getStatus(), "a worker that approves nothing");
        assertEquals(412, claim(null).getStatus(), "a worker that does not say");
        assertEquals("QUEUED", db.sql("SELECT state FROM run_job WHERE organization_id = ?").param(org).query(String.class).single(), "the job waits for a matching worker");
    }

    @Test
    void anExpiredAuthorizationRefusesAtCreationAndAtTheClaim() throws Exception {
        UUID env = environment(REAL, "STAGING");
        authorize(env, Instant.now().plus(Duration.ofDays(30)));
        assertEquals(201, run(env).getStatus());
        // Time passes: the window ends while the run waits in the queue.
        db.sql("UPDATE environment SET authorization_expires_at = now() - interval '1 minute' WHERE organization_id = ? AND id = ?").params(org, env).update();
        assertEquals("AUTHORIZATION_EXPIRED", listed(env).get("lockReason").asString());
        assertEquals("This environment's authorization has expired", detail(run(env)));
        assertEquals(204, claim(APPROVED.fingerprint()).getStatus(), "the queued run is refused as it is claimed");
        assertEquals("This environment's authorization has expired", db.sql("SELECT stop_reason FROM test_run WHERE organization_id = ?").param(org).query(String.class).single());
    }

    @Test
    void revokingStopsARunningJobAtItsNextHeartbeat() throws Exception {
        UUID env = environment(REAL, "STAGING");
        authorize(env, Instant.now().plus(Duration.ofDays(30)));
        assertEquals(201, run(env).getStatus());
        JsonNode job = json.readTree(claim(APPROVED.fingerprint()).getContentAsString());
        String path = "/internal/v1/jobs/" + job.get("jobId").asString() + "/heartbeat";
        String token = job.get("jobToken").asString();
        JsonNode beat = json.readTree(mvc.perform(post(path).header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString());
        assertFalse(beat.get("cancelRequested").asBoolean(), "still authorized");
        call(delete("/api/v1/projects/" + project + "/environments/" + env + "/authorization"), null);
        beat = json.readTree(mvc.perform(post(path).header("Authorization", "Bearer " + beat.get("jobToken").asString())).andReturn().getResponse().getContentAsString());
        assertTrue(beat.get("cancelRequested").asBoolean(), "revoked: the worker is told to stop at this heartbeat");
        assertEquals(1L, db.sql("SELECT count(*) FROM audit_event WHERE organization_id = ? AND action = 'RUN_STOPPED_NOT_ELIGIBLE'").param(org).query(Long.class).single());
        assertEquals("This environment has no written authorization", detail(run(env)));
    }

    @Test
    void aHostTheOperatorDidNotApproveIsLockedWhateverTheProductSays() throws Exception {
        UUID env = environment("https://other.realapp.example/", "STAGING");
        authorize(env, Instant.now().plus(Duration.ofDays(30)));
        assertEquals("HOST_NOT_APPROVED", listed(env).get("lockReason").asString(), "an authorization is not a host approval");
        assertEquals("Real targets are locked until the pre-pilot security gate passes", detail(run(env)));
        UUID port = environment("https://staging.realapp.example:8443/", "STAGING");
        assertEquals("HOST_NOT_APPROVED", listed(port).get("lockReason").asString(), "the approved host on another port is not approved");
    }

    @Test
    void callingARealHostLocalChangesNothing() throws Exception {
        UUID local = environment(REAL, "LOCAL");
        assertEquals("NOT_AUTHORIZED", listed(local).get("lockReason").asString(), "the classification is never a key");
        UUID unapproved = environment("https://elsewhere.realapp.example/", "LOCAL");
        assertEquals("HOST_NOT_APPROVED", listed(unapproved).get("lockReason").asString());
        assertEquals(409, run(unapproved).getStatus());
    }

    @Test
    void approvalChangesAreRecordedWithWhoAndWhy(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("approved-hosts.txt");
        Files.writeString(file, "staging.realapp.example  approved-by=ops@example.com  change=CHG-1  reason=Pilot\n");
        long before = db.sql("SELECT count(*) FROM approved_host_event").query(Long.class).single();
        new ApprovedHostsPolicy(db, file.toString()).recordChanges();
        new ApprovedHostsPolicy(db, file.toString()).recordChanges();
        Files.writeString(file, "# removed\n");
        new ApprovedHostsPolicy(db, file.toString()).recordChanges();
        record E(String host, int port, String event, String by, String change, String reason) {}
        List<E> events = db.sql("SELECT host, port, event, approved_by, change_ref, reason FROM approved_host_event ORDER BY id OFFSET ?").param(before)
                .query((rs, n) -> new E(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))).list();
        assertEquals(List.of(new E("staging.realapp.example", 443, "APPROVED", "ops@example.com", "CHG-1", "Pilot"), new E("staging.realapp.example", 443, "REMOVED", "", "", "")), events,
                "recorded once when approved, once when removed");
        assertThrows(Exception.class, () -> db.sql("DELETE FROM approved_host_event").update(), "the history cannot be rewritten");
        ExecutionLock.configure(APPROVED);
    }
}
