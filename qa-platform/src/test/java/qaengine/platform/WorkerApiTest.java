package qaengine.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import qaengine.core.evidence.LocalEvidenceSink;
import qaengine.platform.evidence.EvidenceService;
import qaengine.platform.internal.JobTokens;
import qaengine.platform.runs.JobQueue;
import qaengine.platform.shared.Principal;

@AutoConfigureMockMvc
@TestPropertySource(properties = "qa.worker-key=test-worker-key-0123456789abcdef0123456789")
class WorkerApiTest extends IntegrationTestBase {

    static final String KEY = "test-worker-key-0123456789abcdef0123456789";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10};

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired JobQueue queue;
    @Autowired JobTokens tokens;
    @Autowired EvidenceService evidence;
    @org.springframework.beans.factory.annotation.Value("${qa.evidence.root}") String evidenceRoot;

    UUID org, project, env, run, job, other, otherProject;
    Principal admin, outsider;

    @BeforeEach
    void seed() {
        db.sql("TRUNCATE organization, app_user CASCADE").update();
        org = UUID.randomUUID(); other = UUID.randomUUID(); project = UUID.randomUUID(); otherProject = UUID.randomUUID();
        UUID target = UUID.randomUUID(); env = UUID.randomUUID(); run = UUID.randomUUID();
        UUID user = UUID.randomUUID(); UUID user2 = UUID.randomUUID();
        db.sql("INSERT INTO organization (id, name) VALUES (?, 'a'), (?, 'b')").params(org, other).update();
        db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?, 'a@x', 'x'), (?, 'b@x', 'x')").params(user, user2).update();
        db.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (?,?, 'ORGANIZATION_ADMIN'), (?,?, 'ORGANIZATION_ADMIN')").params(org, user, other, user2).update();
        admin = new Principal(user, org, "a@x", true);
        outsider = new Principal(user2, other, "b@x", true);
        db.sql("INSERT INTO project (organization_id, id, name) VALUES (?,?, 'p'), (?,?, 'q')").params(org, project, other, otherProject).update();
        db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?, 't')").params(org, target, project).update();
        db.sql("INSERT INTO environment (organization_id, id, target_id, name, classification, base_url) VALUES (?,?,?, 'e', 'LOCAL', 'http://127.0.0.1:1/')").params(org, env, target).update();
        db.sql("INSERT INTO allowed_host (organization_id, environment_id, origin) VALUES (?,?, 'http://127.0.0.1:1')").params(org, env).update();
        db.sql("INSERT INTO execution_policy (organization_id, environment_id) VALUES (?,?)").params(org, env).update();
        db.sql("INSERT INTO test_run (organization_id, id, project_id, environment_id, seed, idempotency_key, created_by) VALUES (?,?,?,?, 1, 'k', ?)").params(org, run, project, env, user).update();
        job = queue.enqueue(org, run, 2, 60);
    }

    static RequestPostProcessor as(Principal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    String claim() throws Exception {
        String json = mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"w\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"jobToken\":\"([^\"]+)\".*", "$1");
    }

    UUID upload(String token, int step, String type, byte[] body) throws Exception {
        String r = mvc.perform(post("/internal/v1/jobs/" + job + "/evidence").header("Authorization", "Bearer " + token).param("step", "" + step).param("type", type)
                .param("sha256", LocalEvidenceSink.sha256(body)).contentType(MediaType.APPLICATION_OCTET_STREAM).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(r.replaceAll(".*\"evidenceId\":\"([^\"]+)\".*", "$1"));
    }

    @Test void claimNeedsTheWorkerKey() throws Exception {
        mvc.perform(post("/internal/v1/jobs/claim").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Key", "wrong").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test void assignmentCarriesThePolicyAndNoSecrets() throws Exception {
        String json = mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getContentAsString();
        assertTrue(json.contains("\"mode\":\"READ_ONLY\""));
        assertTrue(json.contains("http://127.0.0.1:1"));
        assertFalse(json.contains("password"));
    }

    @Test void tokensAreScopedToOneJob() throws Exception {
        String token = claim();
        mvc.perform(post("/internal/v1/jobs/" + UUID.randomUUID() + "/heartbeat").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/jobs/" + job + "/heartbeat").header("Authorization", "Bearer forged.token")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/jobs/" + job + "/heartbeat")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/jobs/" + job + "/heartbeat").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test void staleAttemptGets409OnEveryWrite() throws Exception {
        String stale = claim();
        db.sql("UPDATE run_job SET lease_expires_at = now() - interval '1 second' WHERE id = ?").param(job).update();
        queue.reap();
        claim();
        String path = "/internal/v1/jobs/" + job;
        mvc.perform(post(path + "/heartbeat").header("Authorization", "Bearer " + stale)).andExpect(status().isConflict());
        mvc.perform(post(path + "/steps").header("Authorization", "Bearer " + stale).contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isConflict());
        mvc.perform(post(path + "/complete").header("Authorization", "Bearer " + stale).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"SUCCEEDED\",\"stopReason\":\"EXHAUSTED\",\"steps\":0,\"states\":{}}")).andExpect(status().isConflict());
    }

    @Test void uploadChecksHashAndType() throws Exception {
        String token = claim();
        String path = "/internal/v1/jobs/" + job + "/evidence";
        mvc.perform(post(path).header("Authorization", "Bearer " + token).param("step", "1").param("type", "SCREENSHOT").param("sha256", "0".repeat(64))
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(PNG)).andExpect(status().isConflict());
        byte[] notPng = "<script>".getBytes();
        mvc.perform(post(path).header("Authorization", "Bearer " + token).param("step", "1").param("type", "SCREENSHOT").param("sha256", LocalEvidenceSink.sha256(notPng))
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(notPng)).andExpect(status().isConflict());
    }

    @Test void uploadIsIdempotentAndServerRescans() throws Exception {
        String token = claim();
        assertEquals(upload(token, 1, "SCREENSHOT", PNG), upload(token, 1, "SCREENSHOT", PNG));
        UUID log = upload(token, 2, "SIGNAL_LOG", "password=hunter2xyz".getBytes());
        var body = mvc.perform(get("/api/v1/projects/" + project + "/evidence/" + log + "/content").with(as(admin))).andReturn().getResponse().getContentAsByteArray();
        assertFalse(new String(body, StandardCharsets.UTF_8).contains("hunter2xyz"));
    }

    @Test void retrievalIsAuthorizedAuditedAndSafe() throws Exception {
        String token = claim();
        UUID dom = upload(token, 1, "DOM_SNAPSHOT", "<script>alert(1)</script>".getBytes());
        String url = "/api/v1/projects/" + project + "/evidence/" + dom + "/content";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/" + otherProject + "/evidence/" + dom + "/content").with(as(outsider))).andExpect(status().isNotFound());
        var r = mvc.perform(get(url).with(as(admin))).andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(r.getHeader("Content-Disposition").startsWith("attachment"));
        assertEquals("application/octet-stream", r.getContentType());
        assertEquals("nosniff", r.getHeader("X-Content-Type-Options"));
        assertTrue(r.getHeader("Content-Security-Policy").contains("sandbox"));
        assertEquals(1L, db.sql("SELECT count(*) FROM audit_event WHERE action = 'EVIDENCE_READ' AND object = ?").param(dom.toString()).query(Long.class).single());
    }

    @Test void evidencePathsAreSeparatedPerOrganization() throws Exception {
        UUID id = upload(claim(), 1, "SCREENSHOT", PNG);
        String key = db.sql("SELECT storage_key FROM evidence WHERE id = ?").param(id).query(String.class).single();
        assertTrue(key.startsWith(org + "/"));
    }

    @Test void fullTraceFromDefectToProof() throws Exception {
        String token = claim();
        String path = "/internal/v1/jobs/" + job;
        mvc.perform(post(path + "/start").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        String step = "{\"sequence\":%d,\"parent\":%d,\"kind\":\"NAVIGATE_LINK\",\"url\":\"http://127.0.0.1:1%s\",\"label\":\"x\",\"locators\":[\"a\"],\"verdict\":\"%s\",\"status\":%d,\"state\":\"s\",\"note\":\"\"}";
        mvc.perform(post(path + "/steps").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("[" + step.formatted(1, 0, "/", "PASS", 200) + "," + step.formatted(2, 1, "/crash", "FAIL", 500) + "]")).andExpect(status().isNoContent());
        upload(token, 2, "SCREENSHOT", PNG);
        String finding = "{\"step\":2,\"fingerprint\":\"f1\",\"type\":\"SERVER_ERROR\",\"severity\":\"HIGH\",\"url\":\"http://127.0.0.1:1/crash\",\"title\":\"Page returned a server error\",\"detail\":\"HTTP 500\",\"evidenceSha256\":[\"" + LocalEvidenceSink.sha256(PNG) + "\"]}";
        mvc.perform(post(path + "/findings").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(finding)).andExpect(status().isNoContent());
        mvc.perform(post(path + "/findings").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(finding)).andExpect(status().isNoContent());
        mvc.perform(post(path + "/complete").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"SUCCEEDED\",\"stopReason\":\"EXHAUSTED\",\"steps\":2,\"states\":{}}")).andExpect(status().isNoContent());

        UUID defect = db.sql("SELECT id FROM defect").query(UUID.class).single();
        assertEquals(1, db.sql("SELECT occurrences FROM defect").query(Integer.class).single());
        String trace = mvc.perform(get("/api/v1/projects/" + project + "/defects/" + defect).with(as(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(trace.contains("SCREENSHOT"), "evidence");
        assertTrue(trace.contains("\"url\":\"http://127.0.0.1:1/\""), "repro path starts at the start page");
        assertTrue(trace.contains("testCases\":[\""), "linked test case");
        String cases = mvc.perform(get("/api/v1/projects/" + project + "/test-cases").with(as(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(cases.contains("Reproduce: Page returned a server error at /crash"));
        assertTrue(cases.contains("Page loads: /"));
        mvc.perform(get("/api/v1/projects/" + project + "/defects/" + defect).with(as(outsider))).andExpect(status().isNotFound());
    }

    @Test void retentionKeepsOpenDefectEvidenceAndHonoursHold() throws Exception {
        String token = claim();
        UUID free = upload(token, 1, "SCREENSHOT", PNG);
        UUID held = upload(token, 2, "SIGNAL_LOG", "held".getBytes());
        UUID linked = upload(token, 3, "DOM_SNAPSHOT", "linked".getBytes());
        db.sql("UPDATE evidence SET expires_at = now() - interval '1 day'").update();
        db.sql("UPDATE evidence SET legal_hold = true WHERE id = ?").param(held).update();
        UUID defect = UUID.randomUUID(); UUID finding = UUID.randomUUID();
        db.sql("INSERT INTO defect (organization_id, id, project_id, fingerprint, type, severity, title, url, first_seen_run, last_seen_run) VALUES (?,?,?, 'f', 't', 's', 't', 'u', ?, ?)").params(org, defect, project, run, run).update();
        db.sql("INSERT INTO finding (organization_id, id, run_id, attempt, step, fingerprint, defect_id) VALUES (?,?,?, 1, 3, 'f', ?)").params(org, finding, run, defect).update();
        db.sql("INSERT INTO finding_evidence (organization_id, finding_id, evidence_id) VALUES (?,?,?)").params(org, finding, linked).update();

        assertEquals(1, evidence.deleteExpired());
        assertEquals(free, db.sql("SELECT id FROM evidence WHERE deleted_at IS NOT NULL").query(UUID.class).single());
        mvc.perform(get("/api/v1/projects/" + project + "/evidence/" + free + "/content").with(as(admin))).andExpect(status().isNotFound());
    }

    @Test void tamperingIsDetected() throws Exception {
        UUID id = upload(claim(), 1, "SCREENSHOT", PNG);
        assertEquals(List.of(), evidence.verifySample(10));
        String key = db.sql("SELECT storage_key FROM evidence WHERE id = ?").param(id).query(String.class).single();
        Files.write(Path.of(evidenceRoot).resolve(key), "tampered".getBytes());
        assertEquals(List.of(id), evidence.verifySample(10));
    }
}
