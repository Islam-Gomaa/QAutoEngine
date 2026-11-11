package qaengine.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import qaengine.platform.shared.Principal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Every product endpoint behind the dashboard: lifecycle, roles, isolation and the safety refusals. */
@AutoConfigureMockMvc
class ProductApiTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry r) throws Exception {
        Path f = Files.createTempFile("mk", ".key");
        Files.writeString(f, Base64.getEncoder().encodeToString(new byte[32]));
        r.add("qa.master-key-file", f::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;

    UUID org, otherOrg;
    Principal admin, outsiderAdmin, viewer, tester, lead, stranger;
    UUID project, otherProject;

    Principal user(UUID o, String role) {
        UUID id = UUID.randomUUID();
        String email = id + "@x.test";
        db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?,?, 'x')").params(id, email).update();
        db.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (?,?,?)").params(o, id, role).update();
        return new Principal(id, o, email, role.equals("ORGANIZATION_ADMIN"));
    }

    UUID org(String name) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO organization (id, name) VALUES (?,?)").params(id, name).update();
        return id;
    }

    void member(UUID p, Principal u, String role) {
        db.sql("INSERT INTO project_member (organization_id, project_id, user_id, role) VALUES (?,?,?,?)").params(u.organizationId(), p, u.userId(), role).update();
    }

    MockHttpServletResponse call(Principal p, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.with(authentication(new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))))).with(csrf());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn().getResponse();
    }

    JsonNode ok(Principal p, MockHttpServletRequestBuilder request, Object body) throws Exception {
        var r = call(p, request, body);
        assertTrue(r.getStatus() < 300, () -> "status " + r.getStatus() + " " + contentOf(r));
        String text = r.getContentAsString();
        return text.isEmpty() ? null : json.readTree(text);
    }

    static String contentOf(MockHttpServletResponse r) {
        try {
            return r.getContentAsString();
        } catch (Exception e) {
            return "";
        }
    }

    int status(Principal p, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(p, request, body).getStatus();
    }

    String base() {
        return "/api/v1/projects/" + project;
    }

    @BeforeEach
    void seed() throws Exception {
        org = org("A");
        otherOrg = org("B");
        admin = user(org, "ORGANIZATION_ADMIN");
        outsiderAdmin = user(otherOrg, "ORGANIZATION_ADMIN");
        viewer = user(org, "MEMBER");
        tester = user(org, "MEMBER");
        lead = user(org, "MEMBER");
        stranger = user(org, "MEMBER");
        project = UUID.fromString(ok(admin, post("/api/v1/projects"), java.util.Map.of("name", "Shop " + UUID.randomUUID(), "description", "d")).get("id").asString());
        otherProject = UUID.fromString(ok(outsiderAdmin, post("/api/v1/projects"), java.util.Map.of("name", "Other " + UUID.randomUUID())).get("id").asString());
        member(project, viewer, "VIEWER");
        member(project, tester, "TESTER");
        member(project, lead, "QA_LEAD");
    }

    UUID environment(String url) throws Exception {
        String target = ok(admin, post(base() + "/targets"), java.util.Map.of("name", "App " + UUID.randomUUID(), "type", "WEB")).get("id").asString();
        return UUID.fromString(ok(admin, post(base() + "/targets/" + target + "/environments"),
                java.util.Map.of("name", "QA", "classification", "QA", "baseUrl", url, "description", "e")).get("id").asString());
    }

    UUID suite(String channel) throws Exception {
        return UUID.fromString(ok(lead, post(base() + "/suites"), java.util.Map.of("name", "S " + UUID.randomUUID(), "module", "cart", "channel", channel)).get("id").asString());
    }

    UUID manualCase() throws Exception {
        return UUID.fromString(ok(tester, post(base() + "/test-cases"), java.util.Map.of("title", "Add to cart " + UUID.randomUUID(), "priority", "HIGH",
                "tags", List.of("smoke"), "steps", List.of(java.util.Map.of("action", "Open cart", "expected", "Cart shows")))).get("id").asString());
    }

    @Test
    void projectLifecycleKeepsHistoryAndBecomesReadOnly() throws Exception {
        ok(admin, patch(base()), java.util.Map.of("name", "Renamed " + UUID.randomUUID(), "description", "new"));
        assertEquals("new", ok(viewer, get(base()), null).get("description").asString());
        ok(admin, post(base() + "/archive"), null);
        assertEquals(409, status(tester, post(base() + "/test-cases"), java.util.Map.of("title", "x")));
        assertEquals(200, status(viewer, get(base()), null));
        assertTrue(ok(admin, get("/api/v1/projects?archived=true"), null).size() >= 1);
        assertFalse(ok(admin, get("/api/v1/projects"), null).toString().contains(project.toString()));
        ok(admin, post(base() + "/restore"), null);
        assertEquals(201, status(tester, post(base() + "/test-cases"), java.util.Map.of("title", "x")));
    }

    @Test
    void onlyOrganizationAdminsArchiveProjects() throws Exception {
        member(project, stranger, "PROJECT_ADMIN");
        assertEquals(403, status(stranger, post(base() + "/archive"), null));
    }

    @Test
    void membersCanBeListedChangedAndRemoved() throws Exception {
        ok(admin, put(base() + "/members"), java.util.Map.of("userId", stranger.userId().toString(), "role", "TESTER"));
        assertTrue(ok(viewer, get(base() + "/members"), null).toString().contains(stranger.email()));
        ok(admin, delete(base() + "/members/" + stranger.userId()), null);
        assertEquals(404, status(stranger, get(base()), null));
        assertEquals(404, status(admin, put(base() + "/members"), java.util.Map.of("userId", outsiderAdmin.userId().toString(), "role", "TESTER")));
    }

    @Test
    void fixtureEnvironmentsRunAndRealOnesAreRefusedBeforeQueueing() throws Exception {
        UUID fixture = environment("http://fixture.qa.internal:8090/");
        UUID real = environment("https://shop.example.com/");
        JsonNode list = ok(viewer, get(base() + "/environments"), null);
        for (JsonNode e : list) assertEquals(e.get("id").asString().equals(fixture.toString()), e.get("executable").asBoolean());
        var refused = call(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), java.util.Map.of("environmentId", real.toString()));
        assertEquals(409, refused.getStatus());
        assertEquals(0L, db.sql("SELECT count(*) FROM test_run WHERE organization_id = ? AND environment_id = ?").params(org, real).query(Long.class).single());
        assertEquals(201, status(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), java.util.Map.of("environmentId", fixture.toString())));
    }

    @Test
    void lookalikeHostsAreNotTreatedAsTheFixture() throws Exception {
        UUID fake = environment("http://fixture.qa.internal.evil.example/");
        assertEquals(409, status(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), java.util.Map.of("environmentId", fake.toString())));
    }

    @Test
    void mutationPolicyCannotBeStartedFromTheProduct() throws Exception {
        UUID fixture = environment("http://fixture.qa.internal:8090/");
        assertEquals("READ_ONLY", ok(viewer, get(base() + "/environments"), null).get(0).get("mode").asString());
        db.sql("ALTER TABLE execution_policy DISABLE TRIGGER ALL").update();
        try {
            db.sql("UPDATE execution_policy SET mode = 'AUTHORIZED_MUTATION' WHERE organization_id = ? AND environment_id = ?").params(org, fixture).update();
        } catch (Exception constraintAlreadyRefused) {
            return;
        } finally {
            db.sql("ALTER TABLE execution_policy ENABLE TRIGGER ALL").update();
        }
        assertEquals(409, status(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), java.util.Map.of("environmentId", fixture.toString())));
    }

    @Test
    void anEnvironmentsUrlCannotBeEditedIntoSomethingElse() throws Exception {
        UUID fixture = environment("http://fixture.qa.internal:8090/");
        ok(admin, patch(base() + "/environments/" + fixture), java.util.Map.of("name", "Renamed", "baseUrl", "https://evil.example/", "classification", "PRODUCTION"));
        JsonNode e = ok(viewer, get(base() + "/environments"), null).get(0);
        assertEquals("http://fixture.qa.internal:8090/", e.get("baseUrl").asString());
        assertEquals("QA", e.get("classification").asString());
    }

    @Test
    void archivedEnvironmentsDoNotRun() throws Exception {
        UUID fixture = environment("http://fixture.qa.internal:8090/");
        ok(admin, post(base() + "/environments/" + fixture + "/archive"), null);
        assertEquals(409, status(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), java.util.Map.of("environmentId", fixture.toString())));
    }

    @Test
    void mobileApplicationsAreNotAcceptedYet() throws Exception {
        assertEquals(409, status(admin, post(base() + "/targets"), java.util.Map.of("name", "m", "type", "MOBILE")));
    }

    // Application onboarding (Milestone 1): UAT, active status, base URL rules, and creating an application with its environments.

    String application() throws Exception {
        return ok(admin, post(base() + "/targets"), java.util.Map.of("name", "App " + UUID.randomUUID(), "type", "WEB")).get("id").asString();
    }

    int startRun(UUID environment) throws Exception {
        return status(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), java.util.Map.of("environmentId", environment.toString()));
    }

    JsonNode environmentView(UUID id) throws Exception {
        for (JsonNode e : ok(viewer, get(base() + "/environments"), null)) if (e.get("id").asString().equals(id.toString())) return e;
        throw new AssertionError("environment not listed");
    }

    @Test
    void uatIsAnEnvironmentClassAndExistingRowsDefaultToActive() throws Exception {
        String target = application();
        String url = base() + "/targets/" + target + "/environments";
        JsonNode uat = ok(admin, post(url), java.util.Map.of("name", "uat", "classification", "UAT", "baseUrl", "https://uat.shop.example/"));
        assertEquals("UAT", uat.get("classification").asString());
        assertTrue(uat.get("active").asBoolean());
        assertEquals(409, status(admin, post(url), java.util.Map.of("name", "x", "classification", "SANDBOX", "baseUrl", "https://x.shop.example/")));
        // A row written without the new columns, as every row before the migration was, is active.
        UUID legacy = UUID.randomUUID();
        db.sql("INSERT INTO environment (organization_id, id, target_id, name, classification, base_url) VALUES (?,?,?, 'old', 'QA', 'https://old.shop.example/')")
                .params(org, legacy, UUID.fromString(target)).update();
        assertTrue(db.sql("SELECT e.active AND t.active FROM environment e JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id WHERE e.id = ?")
                .param(legacy).query(Boolean.class).single());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.sql(
                "INSERT INTO environment (organization_id, id, target_id, name, classification, base_url) VALUES (?,?,?, 'bad', 'SANDBOX', 'https://bad.shop.example/')")
                .params(org, UUID.randomUUID(), UUID.fromString(target)).update());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://user:canary-pw@shop.example/", "https://shop.example/#top", "https://shop.example/?token=1", "http://10.1.2.3/", "http://127.0.0.1:8090/",
            "http://[::1]/", "http://2130706433/", "http://169.254.169.254/latest/", "http://metadata.google.internal/", "ftp://shop.example/", "javascript:alert(1)",
            "not a url", "https://shop.example/a/../admin", "https://shop.example\\@evil.example/"})
    void unsafeBaseUrlsAreRefusedAndNothingIsSaved(String url) throws Exception {
        String target = application();
        var refused = call(admin, post(base() + "/targets/" + target + "/environments"), java.util.Map.of("name", "e", "classification", "QA", "baseUrl", url));
        assertEquals(409, refused.getStatus(), contentOf(refused));
        assertFalse(contentOf(refused).contains("canary-pw"), "the refusal must not echo the URL");
        assertEquals(0L, db.sql("SELECT count(*) FROM environment WHERE organization_id = ? AND target_id = ?").params(org, UUID.fromString(target)).query(Long.class).single());
        assertEquals(409, status(admin, post(base() + "/base-url-check"), java.util.Map.of("baseUrl", url)));
    }

    @Test
    void baseUrlsAreStoredNormalizedAndCheckedWithoutContactingAnything() throws Exception {
        UUID env = environment("HTTPS://Shop.Example.COM:443//app");
        assertEquals("https://shop.example.com/app", environmentView(env).get("baseUrl").asString());
        assertEquals("https://shop.example.com", db.sql("SELECT origin FROM allowed_host WHERE organization_id = ? AND environment_id = ?").params(org, env).query(String.class).single());
        JsonNode fixture = ok(admin, post(base() + "/base-url-check"), java.util.Map.of("baseUrl", "http://fixture.qa.internal:8090"));
        assertEquals("http://fixture.qa.internal:8090/", fixture.get("baseUrl").asString());
        assertEquals("http://fixture.qa.internal:8090", fixture.get("origin").asString());
        assertTrue(fixture.get("executable").asBoolean());
        assertFalse(ok(admin, post(base() + "/base-url-check"), java.util.Map.of("baseUrl", "https://shop.example.com")).get("executable").asBoolean(), "real targets stay locked");
        assertEquals(403, status(viewer, post(base() + "/base-url-check"), java.util.Map.of("baseUrl", "https://shop.example.com")));
        assertEquals(404, status(outsiderAdmin, post(base() + "/base-url-check"), java.util.Map.of("baseUrl", "https://shop.example.com")));
    }

    @Test
    void inactiveApplicationsAndEnvironmentsDoNotRunUntilReactivated() throws Exception {
        UUID fixture = environment("http://fixture.qa.internal:8090/");
        String target = environmentView(fixture).get("targetId").asString();
        assertEquals(201, startRun(fixture));

        assertFalse(ok(admin, post(base() + "/environments/" + fixture + "/deactivate"), null).get("active").asBoolean());
        assertEquals(409, startRun(fixture));
        assertTrue(ok(admin, post(base() + "/environments/" + fixture + "/activate"), null).get("active").asBoolean());
        assertEquals(201, startRun(fixture));

        assertFalse(ok(admin, post(base() + "/targets/" + target + "/deactivate"), null).get("active").asBoolean());
        JsonNode view = environmentView(fixture);
        assertTrue(view.get("active").asBoolean());
        assertFalse(view.get("applicationActive").asBoolean());
        assertEquals(409, startRun(fixture), "an inactive application stops its environments");
        ok(admin, post(base() + "/targets/" + target + "/activate"), null);
        assertEquals(201, startRun(fixture));

        List<String> actions = db.sql("SELECT action FROM audit_event WHERE organization_id = ? AND action LIKE '%ACTIVATED' ORDER BY id").param(org).query(String.class).list();
        assertEquals(List.of("ENVIRONMENT_DEACTIVATED", "ENVIRONMENT_ACTIVATED", "APPLICATION_DEACTIVATED", "APPLICATION_ACTIVATED"), actions);
    }

    @Test
    void onlyProjectAdminsSwitchActiveAndArchivedItemsCannotBeSwitched() throws Exception {
        UUID fixture = environment("http://fixture.qa.internal:8090/");
        String target = environmentView(fixture).get("targetId").asString();
        assertEquals(403, status(tester, post(base() + "/environments/" + fixture + "/deactivate"), null));
        assertEquals(403, status(lead, post(base() + "/targets/" + target + "/deactivate"), null));
        assertEquals(404, status(outsiderAdmin, post(base() + "/environments/" + fixture + "/deactivate"), null));
        assertTrue(environmentView(fixture).get("active").asBoolean());
        ok(admin, post(base() + "/environments/" + fixture + "/archive"), null);
        assertEquals(409, status(admin, post(base() + "/environments/" + fixture + "/activate"), null));
        ok(admin, post(base() + "/targets/" + target + "/archive"), null);
        assertEquals(409, status(admin, post(base() + "/targets/" + target + "/deactivate"), null));
    }

    @Test
    void anApplicationAndItsEnvironmentsAreCreatedTogetherOrNotAtAll() throws Exception {
        String name = "Onboarded " + UUID.randomUUID();
        var staging = java.util.Map.of("name", "Staging", "classification", "STAGING", "baseUrl", "https://staging.shop.example/");
        var unsafe = java.util.Map.of("name", "UAT", "classification", "UAT", "baseUrl", "https://u:p@uat.shop.example/");
        assertEquals(409, status(admin, post(base() + "/targets"), java.util.Map.of("name", name, "type", "WEB", "environments", List.of(staging, unsafe))));
        assertEquals(400, status(admin, post(base() + "/targets"), java.util.Map.of("name", name, "type", "WEB", "environments", List.of(java.util.Map.of("name", "", "classification", "QA", "baseUrl", "https://q.shop.example/")))));
        assertEquals(0L, db.sql("SELECT count(*) FROM target WHERE organization_id = ? AND name = ?").params(org, name).query(Long.class).single(), "a refused environment leaves no application behind");
        assertEquals(0L, db.sql("SELECT count(*) FROM environment WHERE organization_id = ? AND base_url = 'https://staging.shop.example/'").param(org).query(Long.class).single());

        var uat = java.util.Map.of("name", "UAT", "classification", "UAT", "baseUrl", "HTTPS://UAT.Shop.Example", "active", false);
        JsonNode created = ok(admin, post(base() + "/targets"), java.util.Map.of("name", name, "type", "WEB", "description", "d", "active", false, "environments", List.of(staging, uat)));
        assertFalse(created.get("active").asBoolean());
        List<JsonNode> environments = new java.util.ArrayList<>();
        for (JsonNode e : ok(viewer, get(base() + "/environments"), null)) if (e.get("targetId").asString().equals(created.get("id").asString())) environments.add(e);
        assertEquals(2, environments.size());
        for (JsonNode e : environments) {
            assertFalse(e.get("applicationActive").asBoolean());
            assertEquals("READ_ONLY", e.get("mode").asString());
            boolean isUat = e.get("classification").asString().equals("UAT");
            assertEquals(!isUat, e.get("active").asBoolean());
            if (isUat) assertEquals("https://uat.shop.example/", e.get("baseUrl").asString());
        }
    }

    @Test
    void suitesFollowTheirLifecycleAndRoles() throws Exception {
        assertEquals(403, status(tester, post(base() + "/suites"), java.util.Map.of("name", "no")));
        UUID s = suite("API");
        ok(lead, patch(base() + "/suites/" + s), java.util.Map.of("name", "Renamed " + s, "channel", "API"));
        ok(lead, post(base() + "/suites/" + s + "/archive"), null);
        assertFalse(ok(viewer, get(base() + "/suites"), null).toString().contains(s.toString()));
        assertTrue(ok(viewer, get(base() + "/suites?archived=true"), null).toString().contains(s.toString()));
        ok(lead, post(base() + "/suites/" + s + "/restore"), null);
    }

    @Test
    void aSuiteOfManualCasesCannotClaimToExecute() throws Exception {
        UUID fixture = environment("http://fixture.qa.internal:8090/");
        UUID s = suite("WEB");
        UUID c = manualCase();
        ok(tester, patch(base() + "/test-cases/" + c), java.util.Map.of("title", "t", "suiteId", s.toString()));
        var r = call(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), java.util.Map.of("environmentId", fixture.toString(), "suiteId", s.toString()));
        assertEquals(409, r.getStatus());
        assertTrue(r.getContentAsString().contains("no automated"));
    }

    @Test
    void aSuiteFromAnotherChannelIsRefused() throws Exception {
        UUID fixture = environment("http://fixture.qa.internal:8090/");
        UUID s = suite("API");
        db.sql("INSERT INTO test_case (organization_id, id, project_id, natural_key, title, suite_id) VALUES (?,?,?,?,?,?)").params(org, UUID.randomUUID(), project, "api:x" + s, "x", s).update();
        assertEquals(409, status(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), java.util.Map.of("environmentId", fixture.toString(), "suiteId", s.toString())));
    }

    @Test
    void pageLimitCannotExceedThePolicy() throws Exception {
        UUID fixture = environment("http://fixture.qa.internal:8090/");
        assertEquals(409, status(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), java.util.Map.of("environmentId", fixture.toString(), "maxPages", 1000000)));
    }

    @Test
    void testCasesVersionTheirStepsAndKeepHistory() throws Exception {
        UUID c = manualCase();
        assertEquals("MANUAL", ok(viewer, get(base() + "/test-cases/" + c), null).get("testCase").get("source").asString());
        JsonNode updated = ok(tester, put(base() + "/test-cases/" + c + "/steps"), List.of(java.util.Map.of("action", "Open", "expected", "Opens"), java.util.Map.of("action", "Pay", "expected", "Paid")));
        assertEquals(2, updated.get("testCase").get("latestVersion").asInt());
        assertEquals(2, updated.get("versions").size());
        ok(tester, post(base() + "/test-cases/" + c + "/results"), java.util.Map.of("verdict", "FAIL", "notes", "broken"));
        JsonNode detail = ok(viewer, get(base() + "/test-cases/" + c), null);
        assertEquals("FAIL", detail.get("testCase").get("lastVerdict").asString());
        assertEquals("broken", detail.get("history").get(0).get("notes").asString());
        assertEquals(403, status(viewer, post(base() + "/test-cases/" + c + "/results"), java.util.Map.of("verdict", "PASS")));
        ok(tester, post(base() + "/test-cases/" + c + "/archive"), null);
        assertEquals(0, ok(viewer, get(base() + "/test-cases"), null).get("total").asInt());
        ok(tester, post(base() + "/test-cases/" + c + "/restore"), null);
    }

    @Test
    void testCaseFiltersWork() throws Exception {
        manualCase();
        assertEquals(1, ok(viewer, get(base() + "/test-cases?priority=HIGH&q=cart"), null).get("total").asInt());
        assertEquals(1, ok(viewer, get(base() + "/test-cases?q=smoke"), null).get("total").asInt());
        assertEquals(0, ok(viewer, get(base() + "/test-cases?priority=LOW"), null).get("total").asInt());
    }

    @Test
    void automatedStepsCannotBeRewritten() throws Exception {
        UUID c = UUID.randomUUID();
        db.sql("INSERT INTO test_case (organization_id, id, project_id, natural_key, title) VALUES (?,?,?,?,?)").params(org, c, project, "page:/x", "x").update();
        assertEquals(409, status(tester, put(base() + "/test-cases/" + c + "/steps"), List.of(java.util.Map.of("action", "a"))));
    }

    @Test
    void aSuiteFromAnotherProjectCannotBeAttached() throws Exception {
        UUID c = manualCase();
        UUID foreign = UUID.fromString(ok(outsiderAdmin, post("/api/v1/projects/" + otherProject + "/suites"), java.util.Map.of("name", "f")).get("id").asString());
        assertEquals(404, status(tester, patch(base() + "/test-cases/" + c), java.util.Map.of("title", "t", "suiteId", foreign.toString())));
    }

    @Test
    void defectsCanBeCreatedTriagedFilteredAndLinked() throws Exception {
        JsonNode d = ok(tester, post(base() + "/defects"), java.util.Map.of("title", "Checkout fails", "severity", "HIGH", "expectedResult", "Paid", "detail", "Spins"));
        String id = d.get("id").asString();
        assertEquals(403, status(tester, patch(base() + "/defects/" + id), java.util.Map.of("status", "TRIAGED")));
        ok(lead, patch(base() + "/defects/" + id), java.util.Map.of("status", "TRIAGED", "priority", "CRITICAL", "module", "checkout"));
        assertEquals(1, ok(viewer, get(base() + "/defects?status=TRIAGED&priority=CRITICAL&q=checkout"), null).get("total").asInt());
        assertEquals(1, ok(viewer, get(base() + "/defects?q=DEF-" + d.get("number").asLong()), null).get("total").asInt());
        assertEquals(0, ok(viewer, get(base() + "/defects?severity=LOW"), null).get("total").asInt());
        assertEquals(200, status(viewer, get(base() + "/defects?sort=severity;DROP&order=asc"), null));
        UUID c = manualCase();
        ok(tester, post(base() + "/test-cases/" + c + "/defects"), java.util.Map.of("defectId", id));
        assertEquals(1, ok(viewer, get(base() + "/defects/" + id), null).get("testCases").size());
        assertEquals(1, ok(viewer, get(base() + "/test-cases/" + c), null).get("defects").size());
        ok(tester, delete(base() + "/test-cases/" + c + "/defects/" + id), null);
        assertEquals(409, status(lead, patch(base() + "/defects/" + id), java.util.Map.of("status", "NOPE")));
    }

    @Test
    void credentialValuesNeverComeBack() throws Exception {
        UUID env = environment("http://fixture.qa.internal:8090/");
        String canary = "canary-" + UUID.randomUUID();
        var body = new java.util.HashMap<String, Object>(java.util.Map.of("name", "login", "username", "u", "password", canary));
        String id = ok(admin, post(base() + "/credentials"), body).get("id").asString();
        ok(admin, put(base() + "/environments/" + env + "/login"), java.util.Map.of("method", "PASSWORD", "credentialId", id, "loginPath", "/login", "submitPath", "/login", "successPath", "/account"));
        for (String path : List.of("/credentials", "/login-profiles", "/environments")) assertFalse(call(admin, get(base() + path), null).getContentAsString().contains(canary), path);
        assertTrue(ok(admin, get(base() + "/credentials"), null).get(0).get("usedBy").toString().contains("QA"));
        assertEquals("login", ok(viewer, get(base() + "/environments"), null).get(0).get("credentialName").asString());
        assertEquals(403, status(tester, get(base() + "/credentials"), null));
        assertEquals(403, status(tester, get(base() + "/login-profiles"), null));
        assertEquals(409, status(admin, delete(base() + "/credentials/" + id), null));
        ok(admin, put(base() + "/credentials/" + id), java.util.Map.of("username", "u", "password", "rotated-" + canary));
        ok(admin, delete(base() + "/environments/" + env + "/login"), null);
        ok(admin, delete(base() + "/credentials/" + id), null);
        assertEquals(0, ok(admin, get(base() + "/credentials"), null).size());
    }

    @Test
    void adminsCannotLockTheOrganizationOut() throws Exception {
        assertEquals(409, status(admin, post("/api/v1/users/" + admin.userId() + "/disable"), null));
        assertEquals(409, status(admin, patch("/api/v1/users/" + admin.userId() + "/role"), java.util.Map.of("role", "MEMBER")));
        assertEquals(403, status(tester, patch("/api/v1/users/" + stranger.userId() + "/role"), java.util.Map.of("role", "ORGANIZATION_ADMIN")));
        assertEquals(404, status(admin, patch("/api/v1/users/" + outsiderAdmin.userId() + "/role"), java.util.Map.of("role", "MEMBER")));
    }

    @Test
    void accountChangesApplyToSessionsAlreadyOpen() throws Exception {
        assertEquals(200, status(tester, get(base()), null));
        ok(admin, post("/api/v1/users/" + tester.userId() + "/disable"), null);
        assertEquals(401, status(tester, get(base()), null));
        ok(admin, post("/api/v1/users/" + tester.userId() + "/enable"), null);
        assertEquals(200, status(tester, get(base()), null));
        ok(admin, patch("/api/v1/users/" + stranger.userId() + "/role"), java.util.Map.of("role", "ORGANIZATION_ADMIN"));
        assertEquals(200, status(stranger, get(base()), null), "promotion applies to a stale principal");
        ok(admin, patch("/api/v1/users/" + stranger.userId() + "/role"), java.util.Map.of("role", "MEMBER"));
        var demoted = new Principal(stranger.userId(), org, stranger.email(), true);
        assertEquals(404, status(demoted, get(base()), null), "a stale admin principal is not trusted");
    }

    @Test
    void passwordsChangeOnlyWithTheCurrentOne() throws Exception {
        assertEquals(409, status(tester, post("/api/v1/auth/password"), java.util.Map.of("current", "wrong", "password", "long-enough-password")));
        assertEquals(400, status(admin, post("/api/v1/users/" + tester.userId() + "/password"), java.util.Map.of("password", "short")));
        assertEquals(403, status(viewer, post("/api/v1/users/" + tester.userId() + "/password"), java.util.Map.of("password", "long-enough-password")));
        assertEquals(200, status(admin, post("/api/v1/users/" + tester.userId() + "/password"), java.util.Map.of("password", "long-enough-password")));
    }

    @Test
    void organizationSettingsAreForAdmins() throws Exception {
        assertEquals(403, status(tester, patch("/api/v1/organization"), java.util.Map.of("name", "x")));
        assertEquals("Renamed", ok(admin, patch("/api/v1/organization"), java.util.Map.of("name", "Renamed")).get("name").asString());
        assertEquals("Renamed", ok(tester, get("/api/v1/organization"), null).get("name").asString());
    }

    @Test
    void dashboardCountsOnlyWhatTheCallerCanSee() throws Exception {
        manualCase();
        JsonNode mine = ok(viewer, get("/api/v1/dashboard"), null);
        assertEquals(1, mine.get("stats").get("projects").asInt());
        assertEquals(1, mine.get("stats").get("testCases").asInt());
        assertTrue(mine.get("stats").get("passRate").isNull(), "no results means no rate, not zero");
        assertEquals(14, mine.get("trend").size());
        assertEquals(0, ok(stranger, get("/api/v1/dashboard"), null).get("stats").get("projects").asInt());
        assertEquals(1, ok(outsiderAdmin, get("/api/v1/dashboard"), null).get("stats").get("projects").asInt());
        assertEquals(0, ok(outsiderAdmin, get("/api/v1/dashboard"), null).get("stats").get("testCases").asInt());
    }

    @Test
    void dashboardComputesRatesFromTheLastAttempt() throws Exception {
        UUID env = environment("http://fixture.qa.internal:8090/");
        String run = ok(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), java.util.Map.of("environmentId", env.toString())).get("id").asString();
        UUID r = UUID.fromString(run);
        for (int[] s : new int[][] {{1, 1, 0}, {1, 2, 0}, {2, 1, 1}, {2, 2, 1}, {2, 3, 0}}) {
            db.sql("INSERT INTO run_step (organization_id, run_id, attempt, sequence, parent, kind, url, verdict) VALUES (?,?,?,?,0,'NAVIGATE_LINK','u',?)")
                    .params(org, r, s[0], s[1], s[2] == 1 ? "PASS" : "FAIL").update();
        }
        JsonNode d = ok(viewer, get("/api/v1/dashboard"), null);
        assertEquals(2, d.get("distribution").get("passed").asInt());
        assertEquals(1, d.get("distribution").get("failed").asInt());
        assertEquals(66.7, d.get("stats").get("passRate").asDouble(), 0.1);
        JsonNode view = ok(viewer, get(base() + "/runs/" + run + "/progress"), null);
        assertEquals(3, view.get("steps").asInt());
        assertEquals(2, ok(viewer, get(base() + "/runs/" + run), null).get("passed").asInt());
    }

    @Test
    void activityKeepsOrganizationEventsFromMembers() throws Exception {
        ok(admin, patch("/api/v1/organization"), java.util.Map.of("name", "Quiet"));
        manualCase();
        assertTrue(ok(admin, get("/api/v1/activity"), null).toString().contains("ORGANIZATION_RENAMED"));
        String seen = ok(viewer, get("/api/v1/activity"), null).toString();
        assertFalse(seen.contains("ORGANIZATION_RENAMED"));
        assertTrue(seen.contains("TEST_CASE_CREATED"));
        assertEquals(0, ok(stranger, get("/api/v1/activity"), null).size());
    }

    @Test
    void searchStaysInsideVisibleProjects() throws Exception {
        String marker = "zebra" + UUID.randomUUID().toString().substring(0, 6);
        ok(tester, post(base() + "/test-cases"), java.util.Map.of("title", marker));
        assertEquals(1, ok(viewer, get("/api/v1/search").param("q", marker), null).size());
        assertEquals(0, ok(stranger, get("/api/v1/search").param("q", marker), null).size());
        assertEquals(0, ok(outsiderAdmin, get("/api/v1/search").param("q", marker), null).size());
    }

    @Test
    void notificationsClearAndStayScoped() throws Exception {
        ok(tester, post(base() + "/defects"), java.util.Map.of("title", "Severe", "severity", "CRITICAL"));
        assertEquals(1, ok(viewer, get("/api/v1/notifications"), null).get("unread").asInt());
        assertEquals(0, ok(stranger, get("/api/v1/notifications"), null).get("items").size());
        ok(viewer, post("/api/v1/notifications/seen"), null);
        assertEquals(0, ok(viewer, get("/api/v1/notifications"), null).get("unread").asInt());
        assertEquals(1, ok(tester, get("/api/v1/notifications"), null).get("unread").asInt());
    }

    @Test
    void reportsAreScopedAndExportsCannotRunFormulas() throws Exception {
        ok(admin, patch(base()), java.util.Map.of("name", "=HYPERLINK(\"http://x\")"));
        JsonNode o = ok(viewer, get("/api/v1/reports/overview"), null);
        assertEquals(1, o.get("projects").size());
        assertEquals(2, o.get("channels").size());
        assertEquals(30, o.get("trend").size());
        assertEquals(200, status(viewer, get("/api/v1/analytics?days=7"), null));
        String csv = call(viewer, get("/api/v1/reports/export.csv?section=projects"), null).getContentAsString();
        assertTrue(csv.contains("'=HYPERLINK"), csv);
        assertEquals(409, status(viewer, get("/api/v1/reports/export.csv?section=nope"), null));
        var book = call(viewer, get("/api/v1/reports/export.xlsx"), null);
        assertEquals(200, book.getStatus());
        assertTrue(book.getHeader("Content-Disposition").startsWith("attachment"));
        assertFalse(ok(outsiderAdmin, get("/api/v1/reports/overview"), null).toString().contains(project.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/members", "/targets", "/environments", "/suites", "/test-cases", "/defects", "/runs", "/evidence", "/credentials", "/login-profiles", "/summary"})
    void anotherOrganizationsProjectDoesNotExist(String path) throws Exception {
        assertEquals(404, status(outsiderAdmin, get(base() + path), null));
        assertEquals(404, status(stranger, get(base() + path), null), "non-members cannot tell the project exists");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/dashboard", "/api/v1/activity", "/api/v1/reports/overview", "/api/v1/analytics", "/api/v1/reports/export.xlsx"})
    void aProjectFilterCannotWidenAccess(String path) throws Exception {
        assertEquals(404, status(outsiderAdmin, get(path).param("projectId", project.toString()), null));
        assertEquals(404, status(stranger, get(path).param("projectId", project.toString()), null));
    }

    @Test
    void writesNeedASessionAndCsrf() throws Exception {
        assertEquals(401, mvc.perform(get("/api/v1/dashboard")).andReturn().getResponse().getStatus());
        var noCsrf = mvc.perform(post(base() + "/archive").with(authentication(new UsernamePasswordAuthenticationToken(admin, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))))).andReturn().getResponse();
        assertEquals(403, noCsrf.getStatus());
        assertNotNull(db.sql("SELECT id FROM project WHERE organization_id = ? AND id = ? AND archived_at IS NULL").params(org, project).query(UUID.class).single());
    }
}
