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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import qaengine.platform.shared.Principal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Written authorization for a real environment: only an Organization Admin records or revokes it, every change is audited,
 * and recording it never unlocks execution.
 */
@AutoConfigureMockMvc
class EnvironmentAuthorizationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry r) throws Exception {
        Path f = Files.createTempFile("mk", ".key");
        Files.writeString(f, Base64.getEncoder().encodeToString(new byte[32]));
        r.add("qa.master-key-file", f::toString);
    }

    static final String REAL = "https://staging.shop.example/";
    static final String REFERENCE = "SHOP-AUTH-2026-014";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;

    UUID org;
    Principal admin, outsiderAdmin, projectAdmin, lead, tester, viewer, stranger;
    UUID project;

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

    void member(Principal u, String role) {
        db.sql("INSERT INTO project_member (organization_id, project_id, user_id, role) VALUES (?,?,?,?)").params(u.organizationId(), project, u.userId(), role).update();
    }

    MockHttpServletResponse call(Principal p, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.with(authentication(new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))))).with(csrf());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn().getResponse();
    }

    JsonNode ok(Principal p, MockHttpServletRequestBuilder request, Object body) throws Exception {
        var r = call(p, request, body);
        assertTrue(r.getStatus() < 300, () -> "status " + r.getStatus());
        String text = r.getContentAsString();
        return text.isEmpty() ? null : json.readTree(text);
    }

    int status(Principal p, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(p, request, body).getStatus();
    }

    String detail(Principal p, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return json.readTree(call(p, request, body).getContentAsString()).get("detail").asString();
    }

    String base() {
        return "/api/v1/projects/" + project;
    }

    String auth(UUID environment) {
        return base() + "/environments/" + environment + "/authorization";
    }

    @BeforeEach
    void seed() throws Exception {
        org = org("A");
        UUID otherOrg = org("B");
        admin = user(org, "ORGANIZATION_ADMIN");
        outsiderAdmin = user(otherOrg, "ORGANIZATION_ADMIN");
        projectAdmin = user(org, "MEMBER");
        lead = user(org, "MEMBER");
        tester = user(org, "MEMBER");
        viewer = user(org, "MEMBER");
        stranger = user(org, "MEMBER");
        project = UUID.fromString(ok(admin, post("/api/v1/projects"), Map.of("name", "Shop " + UUID.randomUUID())).get("id").asString());
        member(projectAdmin, "PROJECT_ADMIN");
        member(lead, "QA_LEAD");
        member(tester, "TESTER");
        member(viewer, "VIEWER");
    }

    UUID environment(String url) throws Exception {
        return environment(Map.of("name", "QA", "classification", "QA", "baseUrl", url));
    }

    UUID environment(Map<String, Object> body) throws Exception {
        String target = ok(admin, post(base() + "/targets"), Map.of("name", "App " + UUID.randomUUID(), "type", "WEB")).get("id").asString();
        return UUID.fromString(ok(admin, post(base() + "/targets/" + target + "/environments"), body).get("id").asString());
    }

    record Stored(String reference, String by, Object at) {}

    Stored stored(UUID environment) {
        return db.sql("SELECT authorization_reference, authorized_by, authorized_at FROM environment WHERE organization_id = ? AND id = ?").params(org, environment)
                .query((rs, n) -> new Stored(rs.getString(1), rs.getString(2), rs.getObject(3))).single();
    }

    record Event(String actor, String action, String detail) {}

    List<Event> audit(UUID environment) {
        return db.sql("SELECT actor, action, detail FROM audit_event WHERE organization_id = ? AND object = ? AND action LIKE 'ENVIRONMENT_AUTHORIZATION_%' ORDER BY id")
                .params(org, environment.toString()).query((rs, n) -> new Event(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    JsonNode listed(Principal p, UUID environment) throws Exception {
        for (JsonNode e : ok(p, get(base() + "/environments"), null)) if (e.get("id").asString().equals(environment.toString())) return e;
        throw new AssertionError("environment not listed");
    }

    @Test
    void anOrganizationAdminRecordsItAndItIsAudited() throws Exception {
        UUID env = environment(REAL);
        assertFalse(listed(viewer, env).get("authorized").asBoolean());

        JsonNode view = ok(admin, put(auth(env)), Map.of("reference", "  " + REFERENCE + " "));
        assertTrue(view.get("authorized").asBoolean());
        assertEquals(REFERENCE, view.get("reference").asString(), "stored trimmed");
        assertEquals(admin.email(), view.get("authorizedBy").asString(), "the recorder comes from the session, never the request");
        assertNotNull(view.get("authorizedAt").asString());

        Stored s = stored(env);
        assertEquals(REFERENCE, s.reference());
        assertEquals(admin.email(), s.by());
        assertNotNull(s.at());
        assertEquals(List.of(new Event(admin.email(), "ENVIRONMENT_AUTHORIZATION_RECORDED", "https://staging.shop.example ref=" + REFERENCE)), audit(env));
    }

    @Test
    void statusIsReadableByProjectAdminsAndListedAsAFlagOnly() throws Exception {
        UUID env = environment(REAL);
        assertFalse(ok(projectAdmin, get(auth(env)), null).get("authorized").asBoolean());
        ok(admin, put(auth(env)), Map.of("reference", REFERENCE));

        JsonNode status = ok(projectAdmin, get(auth(env)), null);
        assertTrue(status.get("authorized").asBoolean());
        assertEquals(REFERENCE, status.get("reference").asString());
        assertEquals(admin.email(), status.get("authorizedBy").asString());

        JsonNode listed = listed(viewer, env);
        assertTrue(listed.get("authorized").asBoolean());
        assertFalse(listed.toString().contains(REFERENCE), "the list shows only whether it is authorized");
        assertFalse(listed.toString().contains(admin.email()));
        for (Principal p : List.of(lead, tester, viewer)) assertEquals(403, status(p, get(auth(env)), null), "the reference is for project admins");
        assertEquals(404, status(stranger, get(auth(env)), null));
    }

    @Test
    void nobodyButAnOrganizationAdminCanChangeIt() throws Exception {
        UUID env = environment(REAL);
        for (Principal p : List.of(projectAdmin, lead, tester, viewer)) {
            assertEquals(403, status(p, put(auth(env)), Map.of("reference", REFERENCE)));
        }
        assertEquals(404, status(stranger, put(auth(env)), Map.of("reference", REFERENCE)), "not a member: the project is not revealed");
        assertEquals(404, status(outsiderAdmin, put(auth(env)), Map.of("reference", REFERENCE)), "another organization's admin");
        assertEquals(new Stored(null, null, null), stored(env));

        ok(admin, put(auth(env)), Map.of("reference", REFERENCE));
        for (Principal p : List.of(projectAdmin, lead, tester, viewer)) assertEquals(403, status(p, delete(auth(env)), null));
        assertEquals(404, status(stranger, delete(auth(env)), null));
        assertEquals(404, status(outsiderAdmin, delete(auth(env)), null));
        assertEquals(REFERENCE, stored(env).reference(), "still recorded");
        assertEquals(1, audit(env).size(), "refusals change nothing and are not recorded as changes");
    }

    @Test
    void aMissingOrInvalidReferenceIsRefused() throws Exception {
        UUID env = environment(REAL);
        Map<String, Object> noReference = new HashMap<>();
        noReference.put("reference", null);
        for (Object body : List.of(Map.of(), noReference, Map.of("reference", ""), Map.of("reference", "   "), Map.of("reference", "ab"),
                Map.of("reference", "x".repeat(121)), Map.of("reference", "REF-1\nforged line"), Map.of("reference", "<script>alert(1)</script>"),
                Map.of("reference", "REF;DROP"))) {
            assertEquals("Authorization reference is missing or invalid", detail(admin, put(auth(env)), body), String.valueOf(body));
        }
        assertEquals(new Stored(null, null, null), stored(env));
        assertTrue(audit(env).isEmpty());
    }

    @Test
    void theFixtureAndArchivedEnvironmentsAreRefused() throws Exception {
        UUID fixture = environment("http://fixture.qa.internal:8090/");
        assertEquals("The local fixture needs no authorization", detail(admin, put(auth(fixture)), Map.of("reference", REFERENCE)));

        UUID env = environment(REAL);
        ok(admin, post(base() + "/environments/" + env + "/archive"), null);
        assertEquals("This environment is archived", detail(admin, put(auth(env)), Map.of("reference", REFERENCE)));
        assertEquals(404, status(admin, put(base() + "/environments/" + UUID.randomUUID() + "/authorization"), Map.of("reference", REFERENCE)));
        assertTrue(audit(fixture).isEmpty());
        assertTrue(audit(env).isEmpty());
    }

    @Test
    void replacingItIsAuditedWithTheOldAndNewReference() throws Exception {
        UUID env = environment(REAL);
        ok(admin, put(auth(env)), Map.of("reference", REFERENCE));
        ok(admin, put(auth(env)), Map.of("reference", REFERENCE));
        assertEquals(1, audit(env).size(), "the same reference again changes nothing");

        Principal second = user(org, "ORGANIZATION_ADMIN");
        JsonNode view = ok(second, put(auth(env)), Map.of("reference", "SHOP-AUTH-2026-015"));
        assertEquals(second.email(), view.get("authorizedBy").asString());
        assertEquals(new Event(second.email(), "ENVIRONMENT_AUTHORIZATION_UPDATED", "https://staging.shop.example ref=" + REFERENCE + " -> SHOP-AUTH-2026-015"), audit(env).get(1));
    }

    @Test
    void revokingClearsItAndIsAudited() throws Exception {
        UUID env = environment(REAL);
        assertEquals("This environment has no authorization", detail(admin, delete(auth(env)), null));
        ok(admin, put(auth(env)), Map.of("reference", REFERENCE));

        assertEquals(204, status(admin, delete(auth(env)), null));
        assertEquals(new Stored(null, null, null), stored(env));
        assertFalse(ok(projectAdmin, get(auth(env)), null).get("authorized").asBoolean());
        assertFalse(listed(viewer, env).get("authorized").asBoolean());
        assertEquals(new Event(admin.email(), "ENVIRONMENT_AUTHORIZATION_REVOKED", "https://staging.shop.example ref=" + REFERENCE), audit(env).get(1));
        assertEquals("This environment has no authorization", detail(admin, delete(auth(env)), null));
        assertEquals(2, audit(env).size());
    }

    @Test
    void anAuthorizedRealTargetStaysLocked() throws Exception {
        UUID env = environment(REAL);
        ok(admin, put(auth(env)), Map.of("reference", REFERENCE));
        JsonNode listed = listed(viewer, env);
        assertTrue(listed.get("authorized").asBoolean());
        assertFalse(listed.get("executable").asBoolean(), "authorization is a record, not a key");
        for (String kind : List.of("EXPLORATION", "AUTH_TEST", "DISCOVERY")) {
            assertEquals("Real targets are locked until the pre-pilot security gate passes",
                    detail(admin, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), Map.of("environmentId", env.toString(), "kind", kind)), kind);
        }
        assertEquals(0L, db.sql("SELECT count(*) FROM test_run WHERE organization_id = ? AND environment_id = ?").params(org, env).query(Long.class).single(), "nothing queued");
    }

    @Test
    void creatingAnEnvironmentCannotAuthorizeIt() throws Exception {
        UUID env = environment(Map.of("name", "QA", "classification", "QA", "baseUrl", REAL, "authorizedBy", "someone@x.test"));
        assertEquals(new Stored(null, null, null), stored(env));
        assertFalse(listed(viewer, env).get("authorized").asBoolean());
    }

    @Test
    void theDatabaseRefusesAHalfRecordedAuthorization() throws Exception {
        UUID env = environment(REAL);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                db.sql("UPDATE environment SET authorized_by = 'x', authorized_at = now() WHERE organization_id = ? AND id = ?").params(org, env).update());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                db.sql("UPDATE environment SET authorization_reference = 'REF-1' WHERE organization_id = ? AND id = ?").params(org, env).update());
    }
}
