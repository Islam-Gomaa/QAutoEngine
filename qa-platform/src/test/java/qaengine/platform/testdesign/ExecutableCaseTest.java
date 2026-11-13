package qaengine.platform.testdesign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import qaengine.contracts.WorkerContracts.ActionReport;
import qaengine.contracts.WorkerContracts.DiscoveredEdge;
import qaengine.contracts.WorkerContracts.DiscoveredPage;
import qaengine.contracts.WorkerContracts.FieldReport;
import qaengine.contracts.WorkerContracts.FormReport;
import qaengine.contracts.WorkerContracts.TableReport;
import qaengine.platform.IntegrationTestBase;
import qaengine.platform.discovery.DiscoveryStore;
import qaengine.platform.shared.Principal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * From discovery to a verdict: generated cases carry scripts built from what was seen, a person approves them, only
 * approved scripts reach a worker, and each result is recorded with why it did not pass. With no AI configured.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"qa.worker-key=test-worker-key-0123456789abcdef0123456789", "qa.master-key-file=${java.io.tmpdir}/qa-test-master.key"})
class ExecutableCaseTest extends IntegrationTestBase {

    static final String KEY = "test-worker-key-0123456789abcdef0123456789";
    static final String BASE = "http://127.0.0.1:1";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired DiscoveryStore discoveries;
    final ObjectMapper json = new ObjectMapper();

    UUID org, project, env, run;
    Principal admin, tester, viewer;

    @BeforeEach
    void seed() throws Exception {
        java.nio.file.Path key = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "qa-test-master.key");
        if (!java.nio.file.Files.exists(key)) java.nio.file.Files.writeString(key, java.util.Base64.getEncoder().encodeToString(new byte[32]));
        db.sql("TRUNCATE organization, app_user CASCADE").update();
        org = UUID.randomUUID(); project = UUID.randomUUID(); env = UUID.randomUUID(); run = UUID.randomUUID();
        UUID a = UUID.randomUUID(), t = UUID.randomUUID(), v = UUID.randomUUID(), target = UUID.randomUUID();
        db.sql("INSERT INTO organization (id, name) VALUES (?, 'a')").param(org).update();
        db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?, 'a@x', 'x'), (?, 't@x', 'x'), (?, 'v@x', 'x')").params(a, t, v).update();
        db.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (?,?, 'ORGANIZATION_ADMIN'), (?,?, 'MEMBER'), (?,?, 'MEMBER')").params(org, a, org, t, org, v).update();
        admin = new Principal(a, org, "a@x", true);
        tester = new Principal(t, org, "t@x", false);
        viewer = new Principal(v, org, "v@x", false);
        db.sql("INSERT INTO project (organization_id, id, name) VALUES (?,?, 'p')").params(org, project).update();
        db.sql("INSERT INTO project_member (organization_id, project_id, user_id, role) VALUES (?,?,?, 'TESTER'), (?,?,?, 'VIEWER')").params(org, project, t, org, project, v).update();
        db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?, 't')").params(org, target, project).update();
        db.sql("INSERT INTO environment (organization_id, id, target_id, name, classification, base_url) VALUES (?,?,?, 'e', 'LOCAL', ?)").params(org, env, target, BASE + "/").update();
        db.sql("INSERT INTO allowed_host (organization_id, environment_id, origin) VALUES (?,?,?)").params(org, env, BASE).update();
        db.sql("INSERT INTO execution_policy (organization_id, environment_id) VALUES (?,?)").params(org, env).update();
        db.sql("INSERT INTO test_run (organization_id, id, project_id, environment_id, seed, idempotency_key, created_by, kind, status, finished_at) VALUES (?,?,?,?, 1, 'k', ?, 'DISCOVERY', 'SUCCEEDED', now())")
                .params(org, run, project, env, a).update();
        var invite = new FormReport("Invite a teammate", "post", "/app/console/invitations", List.of(new FieldReport("Email", "email", "email", true, true)), "Invite user");
        discoveries.page(org, run, 1, new DiscoveredPage("a1", 1, 0, BASE + "/app/console/users", "Users", List.of("Users"), List.of(), List.of(invite),
                List.of(new TableReport("Team members", List.of("Name", "Email"), 3)), List.of(),
                List.of(new ActionReport("LINK", "link", "Billing", BASE + "/app/console/billing", null, "READ_ONLY", "navigates"),
                        new ActionReport("BUTTON", "button", "Delete user", null, null, "DESTRUCTIVE", "named delete"))));
        discoveries.page(org, run, 1, new DiscoveredPage("b2", 2, 1, BASE + "/app/console/billing", "Billing", List.of("Billing"), List.of(), List.of(), List.of(), List.of(), List.of()));
        // The same address after a tab was used on it: a state a script that only opens the page cannot reach.
        discoveries.page(org, run, 1, new DiscoveredPage("a3", 3, 0, BASE + "/app/console/users", "Users", List.of("Users", "Invited"), List.of(), List.of(),
                List.of(new TableReport("Invitations", List.of("Email", "Sent"), 1)), List.of(), List.of()));
        discoveries.edges(org, run, 1, List.of(new DiscoveredEdge("a1", "b2", "Billing"), new DiscoveredEdge("a1", "a3", "Invited")));
    }

    MockHttpServletResponse call(Principal p, MockHttpServletRequestBuilder r, Object body) throws Exception {
        r.with(authentication(new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))))).with(csrf());
        if (body != null) r.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        return mvc.perform(r).andReturn().getResponse();
    }

    JsonNode ok(Principal p, MockHttpServletRequestBuilder r, Object body) throws Exception {
        var res = call(p, r, body);
        assertTrue(res.getStatus() < 300, () -> "status " + res.getStatus() + " " + content(res));
        return res.getContentAsString().isEmpty() ? null : json.readTree(res.getContentAsString());
    }

    static String content(MockHttpServletResponse r) {
        try {
            return r.getContentAsString();
        } catch (Exception e) {
            return "";
        }
    }

    String detail(Principal p, MockHttpServletRequestBuilder r, Object body) throws Exception {
        var res = call(p, r, body);
        assertEquals(409, res.getStatus(), () -> content(res));
        return json.readTree(res.getContentAsString()).get("detail").asString();
    }

    String base() {
        return "/api/v1/projects/" + project;
    }

    UUID caseWithRule(String rule, String titlePart) {
        return db.sql("SELECT id FROM test_case WHERE organization_id = ? AND provenance->>'rule' = ? AND title LIKE ? ORDER BY title LIMIT 1")
                .params(org, rule, "%" + titlePart + "%").query(UUID.class).single();
    }

    JsonNode generated() throws Exception {
        return ok(tester, post(base() + "/runs/" + run + "/discovery/test-cases"), null);
    }

    @Test
    void generatedCasesCarryScriptsBuiltFromWhatWasSeenAndSayWhenTheyCannot() throws Exception {
        JsonNode g = generated();
        assertEquals("rules", g.get("generator").asString(), "no AI is configured, and the response says so");
        assertTrue(g.get("ai").asString().startsWith("DISABLED"), g.get("ai").asString());
        assertTrue(g.get("executable").asInt() >= 4, g.toString());

        JsonNode page = ok(viewer, get(base() + "/test-cases/" + caseWithRule("PAGE_OPENS", "Users")), null);
        assertTrue(page.get("testCase").get("executable").asBoolean());
        assertEquals("READ_ONLY", page.get("executionMode").asString());
        JsonNode steps = json.readTree(page.get("script").asString());
        assertEquals("OPEN", steps.get(0).get("op").asString());
        assertEquals("/app/console/users", steps.get(0).get("path").asString());
        assertEquals("Users", steps.get(1).get("value").asString(), "the title discovery saw");
        JsonNode provenance = page.get("review").get("provenance");
        assertEquals("LOW", provenance.get("risk").asString());
        assertEquals("HIGH", provenance.get("confidence").asString(), "every expected result was observed");

        JsonNode required = ok(viewer, get(base() + "/test-cases/" + caseWithRule("REQUIRED_FIELD", "Email")), null);
        assertEquals("VALIDATION_ONLY", required.get("executionMode").asString(), "emptying a field is typing");
        assertEquals("MEDIUM", required.get("review").get("provenance").get("confidence").asString(), "its expected result is inferred");

        JsonNode destructive = ok(viewer, get(base() + "/test-cases/" + caseWithRule("DESTRUCTIVE_ACTION", "Delete user")), null);
        assertFalse(destructive.get("testCase").get("executable").asBoolean(), "nothing that deletes is ever scripted");
        assertTrue(destructive.get("script").isNull());
        assertEquals("HIGH", destructive.get("review").get("provenance").get("risk").asString());
        assertEquals("LOW", destructive.get("review").get("provenance").get("confidence").asString());

        long invitations = db.sql("SELECT count(*) FROM test_case WHERE organization_id = ? AND title LIKE '%Invitations%' AND 'executable' = ANY(tags)").param(org).query(Long.class).single();
        assertEquals(0, invitations, "a table seen only after using a tab gets no script that would only open the page");
    }

    @Test
    void onlyApprovedScriptsReachAWorkerAndTheirResultsAreRecordedWithTheirCause() throws Exception {
        generated();
        UUID page = caseWithRule("PAGE_OPENS", "Users"), nav = caseWithRule("NAVIGATION", "Billing");
        UUID suite = UUID.fromString(ok(admin, post(base() + "/suites"), Map.of("name", "Smoke", "module", "console", "channel", "WEB")).get("id").asString());
        for (UUID c : List.of(page, nav)) ok(tester, patch(base() + "/test-cases/" + c), Map.of("title", ok(viewer, get(base() + "/test-cases/" + c), null).get("testCase").get("title").asString(), "suiteId", suite.toString()));
        long runnable = ok(viewer, get(base() + "/suites"), null).get(0).get("automatedCases").asLong();
        assertEquals(0, runnable, "nothing waiting for review can run");
        assertEquals("This suite has no automated test cases to run", detail(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()),
                Map.of("environmentId", env.toString(), "suiteId", suite.toString())));

        ok(tester, post(base() + "/test-cases/" + page + "/approve"), Map.of("note", "checked"));
        assertEquals(1, ok(viewer, get(base() + "/suites"), null).get(0).get("automatedCases").asLong());
        ok(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), Map.of("environmentId", env.toString(), "suiteId", suite.toString()));

        String claimed = mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"w\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode job = json.readTree(claimed);
        assertEquals(1, job.get("scripts").size(), "the approved case only");
        JsonNode script = job.get("scripts").get(0);
        assertEquals("READ_ONLY", script.get("executionMode").asString());
        assertEquals("OPEN", json.readTree(script.get("scriptJson").asString()).get(0).get("op").asString());
        String token = job.get("jobToken").asString(), jobId = job.get("jobId").asString();
        UUID version = UUID.fromString(script.get("caseVersionId").asString());
        UUID navVersion = db.sql("SELECT id FROM test_case_version WHERE organization_id = ? AND test_case_id = ? ORDER BY version DESC LIMIT 1").params(org, nav).query(UUID.class).single();

        String path = "/internal/v1/jobs/" + jobId + "/scripts";
        var bad = mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("caseVersionId", version.toString(), "verdict", "PASS", "failureClass", "TIMEOUT", "summary", "x")))).andReturn().getResponse();
        assertEquals(400, bad.getStatus(), "a pass has no failure class");
        var stranger = mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("caseVersionId", UUID.randomUUID().toString(), "verdict", "PASS", "summary", "x")))).andReturn().getResponse();
        assertEquals(403, stranger.getStatus(), "only this suite's cases");
        var ok = mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("caseVersionId", version.toString(), "verdict", "FAIL", "failureClass", "UNEXPECTED_BEHAVIOR",
                        "summary", "Step 3, Expect role heading named \"Users\" to be visible: did not appear")))).andReturn().getResponse();
        assertEquals(204, ok.getStatus());
        assertNotNull(navVersion);

        JsonNode detail = ok(viewer, get(base() + "/test-cases/" + page), null);
        assertEquals("FAIL", detail.get("testCase").get("lastVerdict").asString());
        assertTrue(detail.get("history").get(0).get("notes").asString().startsWith("UNEXPECTED_BEHAVIOR: Step 3"));
    }

    @Test
    void aPersonCanWriteOrEditAScriptAndEveryEditIsValidatedAndReviewedAgain() throws Exception {
        generated();
        UUID page = caseWithRule("PAGE_OPENS", "Users");
        String url = base() + "/test-cases/" + page + "/script";
        assertEquals("A script starts by opening a page", detail(tester, put(url), Map.of("steps", List.of(Map.of("op", "EXPECT_TITLE", "value", "Users")))));
        assertEquals("Step 1 opens a path on the environment, such as /orders", detail(tester, put(url),
                Map.of("steps", List.of(Map.of("op", "OPEN", "path", "https://elsewhere.test/"), Map.of("op", "EXPECT_TITLE", "value", "Users")))));
        var fill = List.of(Map.of("op", "OPEN", "path", "/app/console/users"), Map.of("op", "FILL", "target", Map.of("strategy", "LABEL", "value", "Email"), "value", "x"),
                Map.of("op", "EXPECT_INVALID", "target", Map.of("strategy", "LABEL", "value", "Email")));
        assertEquals("A script that types into fields runs in VALIDATION_ONLY", detail(tester, put(url), Map.of("steps", fill, "executionMode", "READ_ONLY")));
        assertEquals(403, call(viewer, put(url), Map.of("steps", fill)).getStatus());

        ok(tester, post(base() + "/test-cases/" + page + "/approve"), Map.of());
        JsonNode edited = ok(tester, put(url), Map.of("steps", fill));
        assertEquals("VALIDATION_ONLY", edited.get("executionMode").asString(), "chosen from the steps when not given");
        assertEquals("PENDING_REVIEW", edited.get("testCase").get("status").asString(), "approved steps changed, so they are reviewed again");
        int version = edited.get("testCase").get("latestVersion").asInt();

        JsonNode stepsKept = ok(tester, put(base() + "/test-cases/" + page + "/steps"), List.of(Map.of("action", "Open the users page", "expected", "It opens", "basis", "OBSERVED")));
        assertEquals(version + 1, stepsKept.get("testCase").get("latestVersion").asInt());
        assertEquals("VALIDATION_ONLY", stepsKept.get("executionMode").asString(), "editing what a person reads keeps what a worker runs");

        JsonNode removed = ok(tester, put(url), Map.of());
        assertNull(removed.get("script").isNull() ? null : removed.get("script").asString());
        assertFalse(removed.get("testCase").get("executable").asBoolean());
        assertEquals(1L, db.sql("SELECT count(*) FROM audit_event WHERE organization_id = ? AND action = 'TEST_CASE_SCRIPT_REMOVED'").param(org).query(Long.class).single());
    }
}
