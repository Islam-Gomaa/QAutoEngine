package qaengine.platform.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import qaengine.platform.IntegrationTestBase;
import qaengine.platform.shared.Principal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** What a run may do on an environment: set only by an Organization Admin, audited, and never a way past the lock. */
@AutoConfigureMockMvc
class ExecutionModeTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry r) throws Exception {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("mk", ".key");
        java.nio.file.Files.writeString(f, java.util.Base64.getEncoder().encodeToString(new byte[32]));
        r.add("qa.master-key-file", f::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    final ObjectMapper json = new ObjectMapper();
    UUID org, project;
    Principal admin, projectAdmin;

    @BeforeEach
    void seed() throws Exception {
        org = UUID.randomUUID();
        db.sql("INSERT INTO organization (id, name) VALUES (?, 'o')").param(org).update();
        admin = user("ORGANIZATION_ADMIN");
        projectAdmin = user("MEMBER");
        project = UUID.fromString(ok(admin, post("/api/v1/projects"), Map.of("name", "P " + UUID.randomUUID())).get("id").asString());
        db.sql("INSERT INTO project_member (organization_id, project_id, user_id, role) VALUES (?,?,?, 'PROJECT_ADMIN')").params(org, project, projectAdmin.userId()).update();
    }

    Principal user(String role) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?,?, 'x')").params(id, id + "@x.test").update();
        db.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (?,?,?)").params(org, id, role).update();
        return new Principal(id, org, id + "@x.test", role.equals("ORGANIZATION_ADMIN"));
    }

    MockHttpServletResponse call(Principal p, MockHttpServletRequestBuilder r, Object body) throws Exception {
        r.with(authentication(new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))))).with(csrf());
        if (body != null) r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(r).andReturn().getResponse();
    }

    JsonNode ok(Principal p, MockHttpServletRequestBuilder r, Object body) throws Exception {
        var res = call(p, r, body);
        String text = res.getContentAsString();
        assertTrue(res.getStatus() < 300, () -> res.getStatus() + " " + text);
        return text.isEmpty() ? null : json.readTree(text);
    }

    String detail(Principal p, MockHttpServletRequestBuilder r, Object body) throws Exception {
        var res = call(p, r, body);
        String text = res.getContentAsString();
        assertEquals(409, res.getStatus(), text);
        return json.readTree(text).get("detail").asString();
    }

    UUID environment(String url, String classification) throws Exception {
        String target = ok(admin, post("/api/v1/projects/" + project + "/targets"), Map.of("name", "A " + UUID.randomUUID(), "type", "WEB")).get("id").asString();
        return UUID.fromString(ok(admin, post("/api/v1/projects/" + project + "/targets/" + target + "/environments"),
                Map.of("name", "E", "classification", classification, "baseUrl", url, "includePaths", List.of("/app"))).get("id").asString());
    }

    String mode(UUID env) {
        return db.sql("SELECT mode FROM execution_policy WHERE organization_id = ? AND environment_id = ?").params(org, env).query(String.class).single();
    }

    String url(UUID env) {
        return "/api/v1/projects/" + project + "/environments/" + env + "/execution-mode";
    }

    @Test
    void anOrganizationAdminSetsTheModeAndItIsAudited() throws Exception {
        UUID fixture = environment("http://fixture.qa.internal:8090/", "LOCAL");
        assertEquals(403, call(projectAdmin, put(url(fixture)), Map.of("mode", "VALIDATION_ONLY")).getStatus(), "a project admin cannot loosen it");
        assertEquals("VALIDATION_ONLY", ok(admin, put(url(fixture)), Map.of("mode", "VALIDATION_ONLY")).get("mode").asString());
        assertEquals("VALIDATION_ONLY", mode(fixture));
        ok(admin, put(url(fixture)), Map.of("mode", "READ_ONLY"));
        assertEquals(List.of("READ_ONLY -> VALIDATION_ONLY", "VALIDATION_ONLY -> READ_ONLY"), db.sql(
                "SELECT detail FROM audit_event WHERE organization_id = ? AND action = 'ENVIRONMENT_MODE_CHANGED' ORDER BY id").param(org).query(String.class).list());
        assertEquals("Mutation runs are not available", detail(admin, put(url(fixture)), Map.of("mode", "AUTHORIZED_MUTATION")));
        assertEquals("Unknown execution mode", detail(admin, put(url(fixture)), Map.of("mode", "anything")));
    }

    @Test
    void productionAndUnauthorizedRealTargetsStayReadOnly() throws Exception {
        UUID production = environment("https://shop.example/", "PRODUCTION");
        assertEquals("Production environments stay READ_ONLY", detail(admin, put(url(production)), Map.of("mode", "VALIDATION_ONLY")));
        UUID real = environment("https://staging.shop.example/", "STAGING");
        assertEquals("A real target types into fields only under a written authorization in date", detail(admin, put(url(real)), Map.of("mode", "VALIDATION_ONLY")));

        String expires = java.time.Instant.now().plus(java.time.Duration.ofDays(30)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        ok(admin, put("/api/v1/projects/" + project + "/environments/" + real + "/authorization"), Map.of("reference", "AUTH-1", "expiresAt", expires));
        JsonNode set = ok(admin, put(url(real)), Map.of("mode", "VALIDATION_ONLY"));
        assertEquals("VALIDATION_ONLY", set.get("mode").asString());
        assertEquals(false, set.get("executable").asBoolean(), "a mode is not a key: the real target is still locked");
        assertEquals("Real targets are locked until the pre-pilot security gate passes",
                detail(admin, post("/api/v1/projects/" + project + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), Map.of("environmentId", real.toString())));

        call(admin, delete("/api/v1/projects/" + project + "/environments/" + real + "/authorization"), null);
        assertEquals("READ_ONLY", mode(real), "revoking the authorization takes the looser mode with it");
    }
}
