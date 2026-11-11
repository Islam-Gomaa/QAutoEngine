package qaengine.platform.testdesign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import qaengine.contracts.WorkerContracts.ActionReport;
import qaengine.contracts.WorkerContracts.DialogReport;
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

/** Milestone 7: suggested cases join test management waiting for review, and nothing unreviewed is executed or approved blind. */
@AutoConfigureMockMvc
class TestDesignApiTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired DiscoveryStore discoveries;
    final ObjectMapper json = new ObjectMapper();

    UUID org, project, env, run, user, viewerUser;
    Principal admin, viewer, outsider;

    static final String BASE = "http://127.0.0.1:1";

    @BeforeEach
    void seed() {
        db.sql("TRUNCATE organization, app_user CASCADE").update();
        org = UUID.randomUUID(); UUID other = UUID.randomUUID(); project = UUID.randomUUID(); env = UUID.randomUUID(); run = UUID.randomUUID();
        user = UUID.randomUUID(); viewerUser = UUID.randomUUID(); UUID user3 = UUID.randomUUID(); UUID target = UUID.randomUUID();
        db.sql("INSERT INTO organization (id, name) VALUES (?, 'a'), (?, 'b')").params(org, other).update();
        db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?, 'a@x', 'x'), (?, 'v@x', 'x'), (?, 'o@x', 'x')").params(user, viewerUser, user3).update();
        db.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (?,?, 'ORGANIZATION_ADMIN'), (?,?, 'MEMBER'), (?,?, 'ORGANIZATION_ADMIN')").params(org, user, org, viewerUser, other, user3).update();
        admin = new Principal(user, org, "a@x", true);
        viewer = new Principal(viewerUser, org, "v@x", false);
        outsider = new Principal(user3, other, "o@x", true);
        db.sql("INSERT INTO project (organization_id, id, name) VALUES (?,?, 'p')").params(org, project).update();
        db.sql("INSERT INTO project_member (organization_id, project_id, user_id, role) VALUES (?,?,?, 'VIEWER')").params(org, project, viewerUser).update();
        db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?, 't')").params(org, target, project).update();
        db.sql("INSERT INTO environment (organization_id, id, target_id, name, classification, base_url) VALUES (?,?,?, 'e', 'LOCAL', ?)").params(org, env, target, BASE + "/").update();
        db.sql("INSERT INTO test_run (organization_id, id, project_id, environment_id, seed, idempotency_key, created_by, kind, status, finished_at) VALUES (?,?,?,?, 1, 'k', ?, 'DISCOVERY', 'SUCCEEDED', now())")
                .params(org, run, project, env, user).update();
        var invite = new FormReport("Invite a teammate", "post", "/app/console/invitations",
                List.of(new FieldReport("Email", "email", "email", true, true), new FieldReport("", "nickname", "text", false, false)), "Invite user");
        discoveries.page(org, run, 1, new DiscoveredPage("a1", 1, 0, BASE + "/app/console/users", "Users", List.of("Users"), List.of(), List.of(invite),
                List.of(new TableReport("Team members", List.of("Name", "Email"), 3)), List.of(new DialogReport("Remove user", false)),
                List.of(new ActionReport("LINK", "link", "Billing", BASE + "/app/console/billing", null, "READ_ONLY", "navigates"),
                        new ActionReport("BUTTON", "button", "Delete user", null, null, "DESTRUCTIVE", "named delete"),
                        new ActionReport("SUBMIT", "button", "Invite user", null, null, "SENSITIVE", "named invite"))));
        discoveries.page(org, run, 1, new DiscoveredPage("b2", 2, 1, BASE + "/app/console/billing", "Billing", List.of("Billing"), List.of(), List.of(), List.of(), List.of(), List.of()));
        discoveries.edges(org, run, 1, List.of(new DiscoveredEdge("a1", "b2", "Billing")));
    }

    static RequestPostProcessor as(Principal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r, Principal p, String body) throws Exception {
        if (body != null) r = r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r.with(as(p)).with(csrf()));
    }

    JsonNode generate() throws Exception {
        return json.readTree(call(post("/api/v1/projects/" + project + "/runs/" + run + "/discovery/test-cases"), admin, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    UUID caseWithRule(String rule) {
        return db.sql("SELECT id FROM test_case WHERE organization_id = ? AND provenance->>'rule' = ? ORDER BY title LIMIT 1").params(org, rule).query(UUID.class).single();
    }

    String cases(String path) {
        return "/api/v1/projects/" + project + "/test-cases" + path;
    }

    @Test void suggestedCasesWaitForReviewInTestManagementAndAreNeverDuplicated() throws Exception {
        JsonNode r = generate();
        assertEquals("rules", r.get("generator").asString());
        int created = r.get("created").asInt();
        assertTrue(created >= 8, "page, required, format, submit, boundaries, label, destructive, table, navigation: " + created);
        assertEquals(0, r.get("alreadyKnown").asInt());
        assertEquals((long) created, db.sql("SELECT count(*) FROM test_case WHERE source = 'GENERATED' AND status = 'PENDING_REVIEW' AND generated_from_run = ? AND provenance IS NOT NULL")
                .param(run).query(Long.class).single());

        String list = call(get(cases("?source=GENERATED&status=PENDING_REVIEW&size=100")), viewer, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(created, json.readTree(list).get("total").asInt(), "a viewer sees the review queue");

        UUID rejected = caseWithRule("TABLE_COLUMNS");
        call(post(cases("/" + rejected + "/reject")), admin, "{\"note\":\"Rows change daily\"}").andExpect(status().isOk());
        JsonNode again = generate();
        assertEquals(0, again.get("created").asInt(), "nothing comes back twice, rejected or not");
        assertEquals(created, again.get("alreadyKnown").asInt());
        assertEquals("REJECTED", db.sql("SELECT status FROM test_case WHERE id = ?").param(rejected).query(String.class).single());

        JsonNode detail = json.readTree(call(get(cases("/" + rejected)), admin, null).andReturn().getResponse().getContentAsString());
        assertEquals("rules", detail.get("review").get("provenance").get("generator").asString());
        assertEquals(run.toString(), detail.get("review").get("generatedFromRun").asString());
        assertEquals("a@x", detail.get("review").get("reviewedBy").asString());
        assertEquals("Rows change daily", detail.get("review").get("note").asString());
        assertTrue(detail.get("review").get("provenance").get("observed").size() > 0);
    }

    @Test void approvingNeedsEveryExpectedResultStated() throws Exception {
        generate();
        UUID submit = caseWithRule("VALID_SUBMISSION");
        call(post(cases("/" + submit + "/approve")), admin, null).andExpect(status().isConflict());

        JsonNode steps = json.readTree(json.readTree(call(get(cases("/" + submit)), admin, null).andReturn().getResponse().getContentAsString()).get("steps").asString());
        StringBuilder edited = new StringBuilder("[");
        for (JsonNode s : steps) {
            boolean unknown = s.get("basis").asString().equals("UNKNOWN");
            if (edited.length() > 1) edited.append(',');
            edited.append(json.writeValueAsString(java.util.Map.of("action", s.get("action").asString(),
                    "expected", unknown ? "A confirmation says the invitation was sent" : s.get("expected").asString(), "basis", unknown ? "CONFIRMED" : s.get("basis").asString())));
        }
        edited.append(']');
        call(put(cases("/" + submit + "/steps")), admin, "[{\"action\":\"x\",\"expected\":\"y\",\"basis\":\"GUESSED\"}]").andExpect(status().isConflict());
        call(put(cases("/" + submit + "/steps")), admin, edited.toString()).andExpect(status().isOk());
        String approved = call(post(cases("/" + submit + "/approve")), admin, "{\"note\":\"Checked with product\"}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("READY", json.readTree(approved).get("status").asString());
        assertEquals(2, json.readTree(approved).get("latestVersion").asInt(), "the reviewer's edit is a new version");

        UUID required = caseWithRule("REQUIRED_FIELD");
        call(post(cases("/" + required + "/approve")), admin, null).andExpect(status().isOk());
        call(post(cases("/" + required + "/approve")), admin, null).andExpect(status().isConflict());
        call(post(cases("/" + required + "/reject")), admin, null).andExpect(status().isConflict());
    }

    @Test void nothingUnreviewedIsExecutedAndStatusCannotSkipReview() throws Exception {
        generate();
        UUID pending = caseWithRule("PAGE_OPENS");
        call(patch(cases("/" + pending)), admin, "{\"title\":\"Open Users\",\"status\":\"READY\"}").andExpect(status().isConflict());
        call(patch(cases("/" + pending)), admin, "{\"title\":\"Open the Users page\",\"priority\":\"HIGH\"}").andExpect(status().isOk());
        assertEquals("PENDING_REVIEW", db.sql("SELECT status FROM test_case WHERE id = ?").param(pending).query(String.class).single(), "editing keeps it waiting");
        call(post(cases("/" + pending + "/results")), admin, "{\"verdict\":\"PASS\"}").andExpect(status().isConflict());

        UUID suite = UUID.randomUUID();
        db.sql("INSERT INTO test_suite (organization_id, id, project_id, name) VALUES (?,?,?, 'Console')").params(org, suite, project).update();
        call(patch(cases("/" + pending)), admin, "{\"title\":\"Open the Users page\",\"suiteId\":\"" + suite + "\"}").andExpect(status().isOk());
        call(post(cases("/" + pending + "/approve")), admin, null).andExpect(status().isOk());
        String suites = call(get("/api/v1/projects/" + project + "/suites"), admin, null).andReturn().getResponse().getContentAsString();
        assertEquals(0, json.readTree(suites).get(0).get("automatedCases").asInt(), "a suite never runs generated cases, approved or not");
        call(post(cases("/" + pending + "/results")), admin, "{\"verdict\":\"PASS\"}").andExpect(status().isCreated());
    }

    @Test void onlyTestersGenerateAndReviewAndOnlyFromAFinishedDiscoveryInTheirProject() throws Exception {
        call(post("/api/v1/projects/" + project + "/runs/" + run + "/discovery/test-cases"), viewer, null).andExpect(status().isForbidden());
        call(post("/api/v1/projects/" + project + "/runs/" + run + "/discovery/test-cases"), outsider, null).andExpect(status().isNotFound());
        generate();
        call(post(cases("/" + caseWithRule("PAGE_OPENS") + "/approve")), viewer, null).andExpect(status().isForbidden());

        db.sql("UPDATE test_run SET finished_at = NULL WHERE id = ?").param(run).update();
        call(post("/api/v1/projects/" + project + "/runs/" + run + "/discovery/test-cases"), admin, null).andExpect(status().isConflict());
        db.sql("UPDATE test_run SET finished_at = now(), kind = 'EXPLORATION' WHERE id = ?").param(run).update();
        call(post("/api/v1/projects/" + project + "/runs/" + run + "/discovery/test-cases"), admin, null).andExpect(status().isConflict());
        call(post("/api/v1/projects/" + project + "/runs/" + UUID.randomUUID() + "/discovery/test-cases"), admin, null).andExpect(status().isNotFound());
    }

    @Test void suggestionsCountAsTestCasesOnlyOnceApproved() throws Exception {
        int created = generate().get("created").asInt();
        JsonNode coverage = json.readTree(call(get("/api/v1/reports/overview?projectId=" + project), admin, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("coverage").get(0);
        assertEquals(0, coverage.get("total").asInt(), "pending suggestions are not test cases yet");
        assertEquals(created, coverage.get("awaitingReview").asInt());
        JsonNode projects = json.readTree(call(get("/api/v1/projects"), admin, null).andReturn().getResponse().getContentAsString());
        assertEquals(0, projects.get(0).get("testCases").asInt());

        call(post(cases("/" + caseWithRule("REQUIRED_FIELD") + "/approve")), admin, null).andExpect(status().isOk());
        call(post(cases("/" + caseWithRule("PAGE_OPENS") + "/reject")), admin, null).andExpect(status().isOk());
        coverage = json.readTree(call(get("/api/v1/reports/overview?projectId=" + project), admin, null).andReturn().getResponse().getContentAsString()).get("coverage").get(0);
        assertEquals(1, coverage.get("total").asInt(), "approved counts; rejected never does");
        assertEquals(1, coverage.get("generated").asInt());
        assertEquals(created - 2, coverage.get("awaitingReview").asInt());
        String csv = call(get("/api/v1/reports/export.csv?section=coverage&projectId=" + project), admin, null).andReturn().getResponse().getContentAsString();
        assertTrue(csv.startsWith("\"Project\",\"Total\",\"Automated\",\"Manual\""), "existing columns keep their order: " + csv.lines().findFirst().orElse(""));
        assertTrue(csv.lines().findFirst().orElse("").endsWith("\"Generated\",\"Awaiting review\""));
    }

    @Test void theDatabaseRefusesAReviewStateOutsideGeneratedCases() {
        UUID id = UUID.randomUUID();
        var bad = assertThrows(Exception.class, () -> db.sql("INSERT INTO test_case (organization_id, id, project_id, title, natural_key, source, status) VALUES (?,?,?, 't', 'k', 'MANUAL', 'PENDING_REVIEW')")
                .params(org, id, project).update());
        assertTrue(bad.getMessage().contains("test_case_review_only_generated"), bad.getMessage());
        var noProvenance = assertThrows(Exception.class, () -> db.sql("INSERT INTO test_case (organization_id, id, project_id, title, natural_key, source, status) VALUES (?,?,?, 't', 'k', 'GENERATED', 'PENDING_REVIEW')")
                .params(org, id, project).update());
        assertTrue(noProvenance.getMessage().contains("test_case_generated_has_provenance"), noProvenance.getMessage());
    }
}
