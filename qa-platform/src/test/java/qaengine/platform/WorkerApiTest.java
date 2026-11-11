package qaengine.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
@TestPropertySource(properties = {"qa.worker-key=test-worker-key-0123456789abcdef0123456789", "qa.master-key-file=${java.io.tmpdir}/qa-test-master.key"})
class WorkerApiTest extends IntegrationTestBase {

    static final String KEY = "test-worker-key-0123456789abcdef0123456789";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10};

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired JobQueue queue;
    @Autowired JobTokens tokens;
    @Autowired EvidenceService evidence;
    @Autowired qaengine.platform.credentials.CredentialService credentials;
    @org.springframework.beans.factory.annotation.Value("${qa.evidence.root}") String evidenceRoot;

    UUID org, project, env, run, job, other, otherProject;
    Principal admin, outsider;

    static {
        try {
            Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "qa-test-master.key"), java.util.Base64.getEncoder().encodeToString(new byte[32]));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

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
        String step = "{\"sequence\":%d,\"parent\":%d,\"kind\":\"NAVIGATE_LINK\",\"url\":\"http://127.0.0.1:1%s\",\"label\":\"x\",\"locators\":[\"a\"],\"verdict\":\"%s\",\"status\":%d,\"state\":\"s\",\"note\":\"\",\"durationMs\":12}";
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
        assertTrue(trace.contains("testCases\":[{"), "linked test case");
        String cases = mvc.perform(get("/api/v1/projects/" + project + "/test-cases").with(as(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(cases.contains("Reproduce: Page returned a server error at /crash"));
        assertTrue(cases.contains("Page loads: /"));
        mvc.perform(get("/api/v1/projects/" + project + "/defects/" + defect).with(as(outsider))).andExpect(status().isNotFound());
    }

    @Test void suiteRunsCarryTheirScopeAndRecordResultsOnSavedCases() throws Exception {
        UUID suite = UUID.randomUUID(), testCase = UUID.randomUUID(), manual = UUID.randomUUID(), version = UUID.randomUUID();
        db.sql("INSERT INTO test_suite (organization_id, id, project_id, name) VALUES (?,?,?, 's')").params(org, suite, project).update();
        db.sql("INSERT INTO test_case (organization_id, id, project_id, natural_key, title, suite_id) VALUES (?,?,?, 'page:/a', 'A', ?)").params(org, testCase, project, suite).update();
        db.sql("INSERT INTO test_case (organization_id, id, project_id, natural_key, title, suite_id, source) VALUES (?,?,?, 'manual:x', 'M', ?, 'MANUAL')").params(org, manual, project, suite).update();
        db.sql("INSERT INTO test_case_version (organization_id, id, test_case_id, version, steps, content_hash) VALUES (?,?,?, 1, ?::jsonb, 'h')")
                .params(org, version, testCase, "[{\"kind\":\"NAVIGATE_LINK\",\"url\":\"http://127.0.0.1:1/a\",\"label\":\"A\"}]").update();
        db.sql("UPDATE test_run SET suite_id = ? WHERE organization_id = ? AND id = ?").params(suite, org, run).update();
        String json = mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getContentAsString();
        assertTrue(json.contains("\"urls\":[\"http://127.0.0.1:1/a\"]"), json);
        String token = json.replaceAll(".*\"jobToken\":\"([^\"]+)\".*", "$1");
        String path = "/internal/v1/jobs/" + job;
        mvc.perform(post(path + "/start").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(post(path + "/steps").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"sequence\":1,\"parent\":0,\"kind\":\"NAVIGATE_LINK\",\"url\":\"http://127.0.0.1:1/a\",\"label\":\"A\",\"locators\":[],\"verdict\":\"FAIL\",\"status\":500,\"state\":\"s\",\"note\":\"\",\"durationMs\":5}]")).andExpect(status().isNoContent());
        mvc.perform(post(path + "/complete").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"SUCCEEDED\",\"stopReason\":\"EXHAUSTED\",\"steps\":1,\"states\":{}}")).andExpect(status().isNoContent());
        assertEquals("FAIL", db.sql("SELECT verdict FROM test_result WHERE organization_id = ? AND run_id = ? AND test_case_version_id = ?").params(org, run, version).query(String.class).single());
        assertEquals(2L, db.sql("SELECT count(*) FROM test_case WHERE organization_id = ?").param(org).query(Long.class).single(), "a suite run adds no new cases");
        assertEquals(1L, db.sql("SELECT count(*) FROM test_result WHERE organization_id = ? AND run_id = ?").params(org, run).query(Long.class).single(), "manual cases get no automated result");
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

    /** The run is pinned to a profile when it is created, as RunController does. */
    UUID signInWith(UUID credential) {
        UUID profile = UUID.randomUUID();
        db.sql("INSERT INTO auth_profile (organization_id, id, environment_id, name, method, credential_id, is_default, success_path) VALUES (?,?,?, 'Default', 'PASSWORD', ?, true, '/account')")
                .params(org, profile, env, credential).update();
        db.sql("UPDATE test_run SET auth_profile_id = ? WHERE organization_id = ? AND id = ?").params(profile, org, run).update();
        return profile;
    }

    @Test void credentialIsReleasedOncePerAttemptAndOnlyToTheOwner() throws Exception {
        UUID cred = credentials.store(org, project, env, "acct", new qaengine.platform.credentials.CredentialService.Material("u", "canary-value-123", null, null, null, null));
        signInWith(cred);
        String token = claim();
        String url = "/internal/v1/jobs/" + job + "/login";
        mvc.perform(post(url)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/jobs/" + UUID.randomUUID() + "/login").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        String body = mvc.perform(post(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("canary-value-123"));
        mvc.perform(post(url).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        assertEquals(1L, db.sql("SELECT count(*) FROM audit_event WHERE action = 'CREDENTIAL_RELEASED'").query(Long.class).single());
        assertEquals(0L, db.sql("SELECT count(*) FROM audit_event WHERE detail LIKE '%canary-value-123%'").query(Long.class).single());
    }

    @Test void aCredentialDisabledAfterTheRunWasQueuedIsNotReleased() throws Exception {
        UUID cred = credentials.store(org, project, env, "acct", new qaengine.platform.credentials.CredentialService.Material("u", "canary-value-456", null, null, null, null));
        signInWith(cred);
        db.sql("UPDATE credential SET enabled = false WHERE organization_id = ? AND id = ?").params(org, cred).update();
        String token = claim();
        String body = mvc.perform(post("/internal/v1/jobs/" + job + "/login").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("canary-value-456"));
        assertEquals(1L, db.sql("SELECT count(*) FROM audit_event WHERE action = 'CREDENTIAL_REFUSED'").query(Long.class).single());
    }

    @Test void theAssignmentCarriesTheRunsOwnProfile() throws Exception {
        signInWith(credentials.store(org, project, env, "acct", new qaengine.platform.credentials.CredentialService.Material("u", "p", null, null, null, null)));
        String json = mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getContentAsString();
        assertTrue(json.contains("\"login\":{\"method\":\"PASSWORD\""), json);
        assertTrue(json.contains("\"successPath\":\"/account\""), json);
    }

    @Test void aConnectionTestCarriesItsKindAndLoginFlowAndItsReportIsStoredWithoutJunk() throws Exception {
        UUID profile = signInWith(credentials.store(org, project, env, "acct", new qaengine.platform.credentials.CredentialService.Material("u", "p", null, null, null, null)));
        db.sql("UPDATE auth_profile SET locators = '{\"success\":{\"strategy\":\"TEST_ID\",\"value\":\"account-menu\"}}'::jsonb, login_timeout_seconds = 9 WHERE id = ?").param(profile).update();
        db.sql("UPDATE test_run SET kind = 'AUTH_TEST' WHERE id = ?").param(run).update();
        String json = mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getContentAsString();
        assertTrue(json.contains("\"kind\":\"AUTH_TEST\""), json);
        assertTrue(json.contains("\"successElement\":{\"strategy\":\"TEST_ID\",\"value\":\"account-menu\""), json);
        assertTrue(json.contains("\"timeoutSeconds\":9"), json);
        String token = json.replaceAll(".*\"jobToken\":\"([^\"]+)\".*", "$1");
        String url = "/internal/v1/jobs/" + job + "/auth";

        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PWNED\",\"stage\":\"DONE\",\"detail\":\"x\",\"durationMs\":1}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FAILED\",\"failure\":\"TLS_ERROR\",\"stage\":\"DONE\",\"detail\":\"x\",\"durationMs\":1}"))
                .andExpect(status().isUnauthorized());
        String report = "{\"status\":\"FAILED\",\"failure\":\"INVALID_CREDENTIALS\",\"stage\":\"SUBMIT\",\"detail\":\"" + "d".repeat(900) + "\",\"durationMs\":1234,"
                + "\"suggestions\":{\"username\":{\"strategy\":\"LABEL\",\"value\":\"Email\"},\"password\":{\"strategy\":\"XPATH\",\"value\":\"//x\"},\"cookie\":{\"strategy\":\"CSS\",\"value\":\"#c\"}}}";
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(report)).andExpect(status().isNoContent());

        String stored = db.sql("SELECT status || ' ' || failure || ' ' || stage || ' ' || length(detail) || ' ' || duration_ms || ' ' || suggestions::text FROM auth_attempt WHERE run_id = ?")
                .param(run).query(String.class).single();
        assertEquals("FAILED INVALID_CREDENTIALS SUBMIT 500 1234 {\"username\": {\"value\": \"Email\", \"strategy\": \"LABEL\"}}", stored, "unknown fields and unusable locators are dropped");
        String view = mvc.perform(get("/api/v1/projects/" + project + "/runs/" + run + "/auth").with(as(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(view.contains("\"failure\":\"INVALID_CREDENTIALS\""), view);
        mvc.perform(get("/api/v1/projects/" + project + "/runs/" + run + "/auth").with(as(outsider))).andExpect(status().isNotFound());
    }

    @Test void theCodeStepTravelsWithTheJobAndItsEndpointsAreFenced() throws Exception {
        UUID profile = signInWith(credentials.store(org, project, env, "acct", new qaengine.platform.credentials.CredentialService.Material("u", "p", null, null, null, null)));
        db.sql("""
                UPDATE auth_profile SET method = 'PASSWORD_EMAIL_OTP', submit_path = '/app/session-otp', otp_submit_path = '/app/otp-verify', otp_resend_path = '/app/otp-resend',
                       otp_max_resends = 1, otp_locators = '{"field":{"strategy":"LABEL","value":"One-time code"},"resend":{"strategy":"TEST_ID","value":"resend"}}'::jsonb
                WHERE id = ?""").param(profile).update();
        String json = mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getContentAsString();
        assertTrue(json.contains("\"otp\":{\"field\":{\"strategy\":\"LABEL\",\"value\":\"One-time code\""), json);
        assertTrue(json.contains("\"submitPath\":\"/app/otp-verify\",\"resendPath\":\"/app/otp-resend\",\"maxResends\":1,\"waitSeconds\":330"), json);
        String token = json.replaceAll(".*\"jobToken\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/internal/v1/jobs/" + job + "/otp")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/jobs/" + job + "/otp/request")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/jobs/" + UUID.randomUUID() + "/otp").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        String before = mvc.perform(post("/internal/v1/jobs/" + job + "/otp").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(before.contains("\"failure\":\"OTP_NOT_RECEIVED\""), "no code before it was requested: " + before);
        mvc.perform(post("/internal/v1/jobs/" + job + "/otp/request").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        String waiting = mvc.perform(post("/internal/v1/jobs/" + job + "/otp").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(waiting.contains("\"waitingForPerson\":true"), waiting);
        db.sql("UPDATE run_job SET state = 'FAILED' WHERE id = ?").param(job).update();
        mvc.perform(post("/internal/v1/jobs/" + job + "/otp").header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
    }

    @Test void signingInAgainAfterTheSessionEndedIsKeptBesideTheFirstSignInAndNeverMore() throws Exception {
        signInWith(credentials.store(org, project, env, "acct", new qaengine.platform.credentials.CredentialService.Material("u", "p", null, null, null, null)));
        String token = claim();
        String url = "/internal/v1/jobs/" + job + "/auth";
        // A worker from before signing in again sends no number: that is the run's one sign-in.
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"AUTHENTICATED\",\"stage\":\"DONE\",\"detail\":\"ok\",\"durationMs\":5}")).andExpect(status().isNoContent());
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"AUTHENTICATED\",\"stage\":\"DONE\",\"detail\":\"ok\",\"durationMs\":7,\"signIn\":2}")).andExpect(status().isNoContent());
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"AUTHENTICATED\",\"stage\":\"DONE\",\"detail\":\"ok\",\"durationMs\":7,\"signIn\":3}")).andExpect(status().isBadRequest());
        assertEquals(List.of(1, 2), db.sql("SELECT sign_in FROM auth_attempt WHERE run_id = ? ORDER BY sign_in").param(run).query(Integer.class).list());
        String view = mvc.perform(get("/api/v1/projects/" + project + "/runs/" + run + "/auth").with(as(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(view.indexOf("\"signIn\":1") < view.indexOf("\"signIn\":2"), view);
        assertEquals(1L, db.sql("SELECT count(*) FROM audit_event WHERE action = 'AUTH_ATTEMPT_RECORDED' AND detail LIKE 'signed in again:%'").query(Long.class).single());
    }

    static String page(String fingerprint, int depth, String url, String actionsJson) {
        return "{\"fingerprint\":\"" + fingerprint + "\",\"step\":" + (depth + 1) + ",\"depth\":" + depth + ",\"url\":\"" + url + "\",\"title\":\"Users\",\"headings\":[\"Users\"],"
                + "\"navigation\":[{\"name\":\"Console\",\"links\":[\"Users\",\"Billing\"]}],"
                + "\"forms\":[{\"name\":\"Invite\",\"method\":\"post\",\"action\":\"/invitations\",\"submitLabel\":\"Invite user\",\"fields\":["
                + "{\"label\":\"Email\",\"name\":\"email\",\"type\":\"email\",\"required\":true,\"labelled\":true},{\"label\":\"\",\"name\":\"nick\",\"type\":\"text\",\"required\":false,\"labelled\":false}]}],"
                + "\"tables\":[{\"caption\":\"Team\",\"headers\":[\"Name\"],\"rows\":3}],\"dialogs\":[{\"name\":\"Remove user\",\"open\":false}],\"actions\":" + actionsJson + "}";
    }

    static final String ACTIONS = "[{\"kind\":\"LINK\",\"role\":\"link\",\"name\":\"Billing\",\"href\":\"http://127.0.0.1:1/billing\",\"locator\":{\"strategy\":\"ROLE\",\"value\":\"link\",\"name\":\"Billing\"},\"safety\":\"READ_ONLY\",\"reason\":\"navigates\"},"
            + "{\"kind\":\"SUBMIT\",\"role\":\"button\",\"name\":\"Invite user\",\"safety\":\"SENSITIVE\",\"reason\":\"named invite\"},"
            + "{\"kind\":\"BUTTON\",\"role\":\"button\",\"name\":\"Delete user\",\"locator\":{\"strategy\":\"TEST_ID\",\"value\":\"delete-user\"},\"safety\":\"DESTRUCTIVE\",\"reason\":\"named delete\"}]";

    org.springframework.test.web.servlet.ResultActions report(String token, String path, String body) throws Exception {
        return mvc.perform(post("/internal/v1/jobs/" + job + path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test void discoveryKeepsEachPageOnceRefusesWhatACorrectWorkerNeverSendsAndMintsNoRegressionCases() throws Exception {
        db.sql("UPDATE test_run SET kind = 'DISCOVERY' WHERE id = ?").param(run).update();
        String token = claim();
        report(token, "/discovery/pages", page("aa11", 0, "http://127.0.0.1:1/users", ACTIONS)).andExpect(status().isNoContent());
        report(token, "/discovery/pages", page("aa11", 0, "http://127.0.0.1:1/users", ACTIONS)).andExpect(status().isNoContent());
        report(token, "/discovery/pages", page("bb22", 1, "http://127.0.0.1:1/billing", "[]")).andExpect(status().isNoContent());
        assertEquals(2L, db.sql("SELECT count(*) FROM discovered_page WHERE run_id = ?").param(run).query(Long.class).single(), "a state is kept once");
        assertEquals(3L, db.sql("SELECT count(*) FROM discovered_action").query(Long.class).single());

        report(token, "/discovery/pages", page("cc33", 0, "x", ACTIONS.replace("DESTRUCTIVE", "HARMLESS"))).andExpect(status().isBadRequest());
        report(token, "/discovery/pages", page("cc33", 0, "x", ACTIONS.replace("\"SUBMIT\"", "\"SCRIPT\""))).andExpect(status().isBadRequest());
        report(token, "/discovery/pages", page("cc33", 0, "x", ACTIONS.replace("\"TEST_ID\"", "\"XPATH\""))).andExpect(status().isBadRequest());
        report(token, "/discovery/pages", page("not-hex'--", 0, "x", "[]")).andExpect(status().isBadRequest());
        String many = "[" + String.join(",", java.util.Collections.nCopies(201, "{\"kind\":\"LINK\",\"name\":\"x\",\"safety\":\"READ_ONLY\",\"reason\":\"r\"}")) + "]";
        report(token, "/discovery/pages", page("dd44", 0, "x", many)).andExpect(status().isBadRequest());
        report(token, "/discovery/edges", "[{\"from\":\"aa11\",\"to\":\"bb22\",\"via\":\"Billing\"},{\"from\":\"aa11\",\"to\":\"bb22\",\"via\":\"Billing\"}]").andExpect(status().isNoContent());
        report(token, "/discovery/edges", "[{\"from\":\"aa11\",\"to\":\"<script>\",\"via\":\"x\"}]").andExpect(status().isBadRequest());

        mvc.perform(post("/internal/v1/jobs/" + job + "/start").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        db.sql("INSERT INTO run_step (organization_id, run_id, attempt, sequence, parent, kind, url, verdict) VALUES (?,?,1,1,0,'NAVIGATE_LINK','http://127.0.0.1:1/users','PASS')").params(org, run).update();
        report(token, "/complete", "{\"outcome\":\"SUCCEEDED\",\"stopReason\":\"EXHAUSTED\",\"steps\":1,\"states\":{}}").andExpect(status().isNoContent());
        assertEquals(0L, db.sql("SELECT count(*) FROM test_case WHERE organization_id = ?").param(org).query(Long.class).single(), "discovery mints no regression cases");

        String summary = mvc.perform(get("/api/v1/projects/" + project + "/runs/" + run + "/discovery").with(as(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var sj = new tools.jackson.databind.ObjectMapper().readTree(summary);
        assertEquals(2, sj.get("pages").asInt());
        assertEquals(2, sj.get("forms").asInt());
        assertEquals(4, sj.get("fields").asInt());
        assertEquals(2, sj.get("requiredFields").asInt());
        assertEquals(2, sj.get("unlabelledFields").asInt());
        assertEquals(1, sj.get("links").asInt());
        assertEquals(1, sj.get("actions").get("DESTRUCTIVE").asInt());
        assertEquals(1, sj.get("actions").get("SENSITIVE").asInt());
        assertEquals("http://127.0.0.1:1/users", sj.get("pageRows").get(0).get("url").asString(), "shallowest first");

        String pageId = sj.get("pageRows").get(0).get("id").asString();
        String detail = mvc.perform(get("/api/v1/projects/" + project + "/runs/" + run + "/discovery/pages/" + pageId).with(as(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var dj = new tools.jackson.databind.ObjectMapper().readTree(detail);
        assertEquals("Invite", dj.get("structure").get("forms").get(0).get("name").asString());
        assertEquals("DESTRUCTIVE", dj.get("actions").get(2).get("safety").asString());
        assertEquals("delete-user", dj.get("actions").get(2).get("locator").get("value").asString());
        assertEquals("http://127.0.0.1:1/billing", dj.get("linksTo").get(0).get("url").asString());
        mvc.perform(get("/api/v1/projects/" + project + "/runs/" + run + "/discovery").with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/" + project + "/runs/" + run + "/discovery/pages/" + UUID.randomUUID()).with(as(admin))).andExpect(status().isNotFound());
    }

    @Test void onlyADiscoveryRunReportsPagesAndDiscoveryIsForWebWithoutASuite() throws Exception {
        String token = claim();
        report(token, "/discovery/pages", page("aa11", 0, "x", "[]")).andExpect(status().isConflict());
        String created = mvc.perform(post("/api/v1/projects/" + project + "/runs").with(as(admin)).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"environmentId\":\"" + env + "\",\"kind\":\"DISCOVERY\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertTrue(created.contains("\"kind\":\"DISCOVERY\""), created);
        mvc.perform(post("/api/v1/projects/" + project + "/runs").with(as(admin)).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"environmentId\":\"" + env + "\",\"kind\":\"DISCOVERY\",\"suiteId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isConflict());
    }

    @Test void tamperingIsDetected() throws Exception {
        UUID id = upload(claim(), 1, "SCREENSHOT", PNG);
        assertEquals(List.of(), evidence.verifySample(10));
        String key = db.sql("SELECT storage_key FROM evidence WHERE id = ?").param(id).query(String.class).single();
        Files.write(Path.of(evidenceRoot).resolve(key), "tampered".getBytes());
        assertEquals(List.of(id), evidence.verifySample(10));
    }
}
