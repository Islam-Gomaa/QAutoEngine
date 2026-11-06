package qaengine.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import qaengine.platform.identity.SecretBox;
import qaengine.platform.identity.Totp;
import qaengine.platform.shared.Principal;

@AutoConfigureMockMvc
class ApiSecurityTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry r) throws Exception {
        Path f = Files.createTempFile("mk", ".key");
        Files.writeString(f, Base64.getEncoder().encodeToString(new byte[32]));
        r.add("qa.master-key-file", f::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired PasswordEncoder encoder;
    @Autowired SecretBox box;

    UUID orgA, orgB, projectA, projectB, runB;
    Principal adminA, adminB, viewerA, testerA, outsiderA;

    Principal user(UUID org, String role) {
        UUID id = UUID.randomUUID();
        String email = id + "@x.test";
        db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?,?, 'x')").params(id, email).update();
        db.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (?,?,?)").params(org, id, role).update();
        return new Principal(id, org, email, role.equals("ORGANIZATION_ADMIN"));
    }

    UUID project(UUID org) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO project (organization_id, id, name) VALUES (?,?,?)").params(org, id, id.toString()).update();
        return id;
    }

    void member(UUID org, UUID project, Principal p, String role) {
        db.sql("INSERT INTO project_member (organization_id, project_id, user_id, role) VALUES (?,?,?,?)").params(org, project, p.userId(), role).update();
    }

    static RequestPostProcessor as(Principal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @BeforeEach
    void seed() {
        db.sql("TRUNCATE organization, app_user CASCADE").update();
        orgA = UUID.randomUUID();
        orgB = UUID.randomUUID();
        db.sql("INSERT INTO organization (id, name) VALUES (?, 'a'), (?, 'b')").params(orgA, orgB).update();
        adminA = user(orgA, "ORGANIZATION_ADMIN");
        adminB = user(orgB, "ORGANIZATION_ADMIN");
        viewerA = user(orgA, "MEMBER");
        testerA = user(orgA, "MEMBER");
        outsiderA = user(orgA, "MEMBER");
        projectA = project(orgA);
        projectB = project(orgB);
        member(orgA, projectA, viewerA, "VIEWER");
        member(orgA, projectA, testerA, "TESTER");
    }

    @Test void everythingIsDeniedByDefault() throws Exception {
        for (String path : List.of("/api/v1/projects", "/api/v1/auth/me", "/api/v1/projects/" + projectA + "/runs", "/actuator/prometheus", "/anything")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test void writesNeedCsrf() throws Exception {
        mvc.perform(post("/api/v1/projects").with(as(adminA)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test void securityHeadersAreSet() throws Exception {
        var r = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        assertTrue(r.getHeader("Content-Security-Policy").contains("frame-ancestors 'none'"));
        assertEquals("nosniff", r.getHeader("X-Content-Type-Options"));
    }

    @Test void otherOrganizationsAreInvisible() throws Exception {
        String other = "/api/v1/projects/" + projectB;
        for (String path : List.of(other + "/targets", other + "/environments", other + "/runs", other + "/runs/" + UUID.randomUUID())) {
            mvc.perform(get(path).with(as(adminA))).andExpect(status().isNotFound());
        }
        mvc.perform(post(other + "/targets").with(as(adminA)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
    }

    @Test void projectListIsOrganizationAndMembershipScoped() throws Exception {
        String a = mvc.perform(get("/api/v1/projects").with(as(adminA))).andReturn().getResponse().getContentAsString();
        assertTrue(a.contains(projectA.toString()));
        assertFalse(a.contains(projectB.toString()));
        String outsider = mvc.perform(get("/api/v1/projects").with(as(outsiderA))).andReturn().getResponse().getContentAsString();
        assertFalse(outsider.contains(projectA.toString()));
    }

    @ParameterizedTest
    @CsvSource({"viewer,200,403,403", "tester,200,403,404", "outsider,404,404,404", "admin,200,201,404"})
    void roleMatrix(String who, int read, int createTarget, int createRun) throws Exception {
        Principal p = switch (who) { case "viewer" -> viewerA; case "tester" -> testerA; case "outsider" -> outsiderA; default -> adminA; };
        String base = "/api/v1/projects/" + projectA;
        mvc.perform(get(base + "/targets").with(as(p))).andExpect(status().is(read));
        mvc.perform(post(base + "/targets").with(as(p)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"t" + who + "\"}")).andExpect(status().is(createTarget));
        mvc.perform(post(base + "/runs").with(as(p)).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"environmentId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().is(createRun));
    }

    private UUID environment() throws Exception {
        UUID t = UUID.randomUUID();
        UUID e = UUID.randomUUID();
        db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?, 't')").params(orgA, t, projectA).update();
        db.sql("INSERT INTO environment (organization_id, id, target_id, name, classification, base_url) VALUES (?,?,?, 'e', 'LOCAL', 'http://127.0.0.1')").params(orgA, e, t).update();
        db.sql("INSERT INTO execution_policy (organization_id, environment_id) VALUES (?,?)").params(orgA, e).update();
        return e;
    }

    @Test void runCreationIsIdempotent() throws Exception {
        UUID env = environment();
        String body = "{\"environmentId\":\"" + env + "\"}";
        var first = mvc.perform(post("/api/v1/projects/" + projectA + "/runs").with(as(testerA)).with(csrf()).header("Idempotency-Key", "k1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var second = mvc.perform(post("/api/v1/projects/" + projectA + "/runs").with(as(testerA)).with(csrf()).header("Idempotency-Key", "k1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first.substring(0, 44), second.substring(0, 44));
        assertEquals(1L, db.sql("SELECT count(*) FROM run_job").query(Long.class).single());
    }

    @Test void productionNeedsIncludePaths() throws Exception {
        UUID t = UUID.randomUUID();
        db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?, 't')").params(orgA, t, projectA).update();
        String url = "/api/v1/projects/" + projectA + "/targets/" + t + "/environments";
        mvc.perform(post(url).with(as(adminA)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"p\",\"classification\":\"PRODUCTION\",\"baseUrl\":\"https://app.test\"}")).andExpect(status().isConflict());
        mvc.perform(post(url).with(as(adminA)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"p\",\"classification\":\"PRODUCTION\",\"baseUrl\":\"https://app.test\",\"includePaths\":[\"/reports\"]}")).andExpect(status().isCreated());
    }

    private void account(String email, String password, String totp) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO app_user (id, email, password_hash, totp_secret) VALUES (?,?,?,?)").params(id, email, encoder.encode(password), totp).update();
        db.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (?,?, 'MEMBER')").params(orgA, id).update();
    }

    private int login(String email, String password, String totp) throws Exception {
        String t = totp == null ? "null" : "\"" + totp + "\"";
        return mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"totp\":" + t + "}")).andReturn().getResponse().getStatus();
    }

    @Test void loginWorksAndFailsWithoutRevealingWho() throws Exception {
        account("a@x.test", "correct-horse", null);
        assertEquals(200, login("a@x.test", "correct-horse", null));
        assertEquals(401, login("a@x.test", "wrong", null));
        assertEquals(401, login("nobody@x.test", "wrong", null));
    }

    private UUID channelEnvironment(String channel) {
        UUID t = UUID.randomUUID();
        UUID e = UUID.randomUUID();
        db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?, 't')").params(orgA, t, projectA).update();
        db.sql("INSERT INTO environment (organization_id, id, target_id, name, classification, base_url, channel) VALUES (?,?,?, 'e', 'LOCAL', 'http://127.0.0.1', ?)").params(orgA, e, t, channel).update();
        return e;
    }

    private String credential() throws Exception {
        String body = mvc.perform(post("/api/v1/projects/" + projectA + "/credentials").with(as(adminA)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"c" + UUID.randomUUID() + "\",\"password\":\"token-canary-value\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("token-canary-value"));
        return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    private int configureLogin(UUID env, String method, String cred) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/projects/" + projectA + "/environments/" + env + "/login").with(as(adminA)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"" + method + "\",\"credentialId\":\"" + cred + "\",\"successPath\":\"/a\"}")).andReturn().getResponse().getStatus();
    }

    @Test void loginMethodMustFitTheChannel() throws Exception {
        String cred = credential();
        assertEquals(409, configureLogin(channelEnvironment("API"), "PASSWORD", cred), "a password must not become a bearer token");
        assertEquals(409, configureLogin(channelEnvironment("WEB"), "API_TOKEN", cred));
        assertEquals(200, configureLogin(channelEnvironment("API"), "API_TOKEN", cred));
        assertEquals(200, configureLogin(channelEnvironment("WEB"), "PASSWORD", cred));
    }

    @Test void apiEnvironmentInputsAreChecked() throws Exception {
        UUID t = UUID.randomUUID();
        db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?, 't')").params(orgA, t, projectA).update();
        String url = "/api/v1/projects/" + projectA + "/targets/" + t + "/environments";
        for (String bad : List.of("\"channel\":\"FTP\"", "\"channel\":\"API\",\"apiSpecPath\":\"http://evil.test/x\"", "\"channel\":\"API\",\"apiSeedPaths\":[\"http://evil.test\"]")) {
            mvc.perform(post(url).with(as(adminA)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"e\",\"classification\":\"LOCAL\",\"baseUrl\":\"http://127.0.0.1:1\"," + bad + "}")).andExpect(status().isConflict());
        }
    }

    @Test void apiMetricsAreScoped() throws Exception {
        String path = "/api/v1/projects/" + projectB + "/runs/" + UUID.randomUUID() + "/api-metrics";
        mvc.perform(get(path).with(as(adminA))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/" + projectA + "/runs/" + UUID.randomUUID() + "/api-metrics").with(as(viewerA))).andExpect(status().isNotFound());
    }

    @Test void passwordsAreArgon2() {
        assertTrue(encoder.encode("x").startsWith("$argon2id$"));
    }

    @Test void totpIsRequiredWhenEnrolledAndStoredSealed() throws Exception {
        account("t@x.test", "correct-horse", null);
        UUID id = db.sql("SELECT id FROM app_user WHERE email = 't@x.test'").query(UUID.class).single();
        var me = new Principal(id, orgA, "t@x.test", false);
        String body = mvc.perform(post("/api/v1/auth/totp").with(as(me)).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String seed = body.replaceAll(".*\"seedBase64\":\"([^\"]+)\".*", "$1");
        byte[] key = Base64.getDecoder().decode(seed);
        assertFalse(db.sql("SELECT totp_secret FROM app_user WHERE id = ?").param(id).query(String.class).single().contains(seed), "seed is sealed at rest");
        assertEquals(401, login("t@x.test", "correct-horse", null));
        assertEquals(401, login("t@x.test", "correct-horse", "000000".equals(Totp.code(key, Instant.now().getEpochSecond() / 30)) ? "111111" : "000000"));
        assertEquals(200, login("t@x.test", "correct-horse", Totp.code(key, Instant.now().getEpochSecond() / 30)));
    }

    @Test void onlyAdminsManageUsersAndOnlyTheirOwn() throws Exception {
        String body = "{\"email\":\"new@x.test\",\"password\":\"long-enough-password\",\"organizationAdmin\":false}";
        mvc.perform(post("/api/v1/users").with(as(testerA)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/users").with(as(adminA)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"s@x.test\",\"password\":\"short\",\"organizationAdmin\":false}")).andExpect(status().isBadRequest());
        String created = mvc.perform(post("/api/v1/users").with(as(adminA)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertFalse(created.contains("long-enough-password"));
        assertEquals(200, login("new@x.test", "long-enough-password", null));
        UUID id = db.sql("SELECT id FROM app_user WHERE email = 'new@x.test'").query(UUID.class).single();
        mvc.perform(post("/api/v1/users/" + id + "/disable").with(as(adminB)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/users/" + id + "/disable").with(as(adminA)).with(csrf())).andExpect(status().isOk());
        assertEquals(401, login("new@x.test", "long-enough-password", null));
        String listB = mvc.perform(get("/api/v1/users").with(as(adminB))).andReturn().getResponse().getContentAsString();
        assertFalse(listB.contains("new@x.test"), "other organizations are invisible");
    }

    @Test void loginIsThrottled() throws Exception {
        account("r@x.test", "correct-horse", null);
        for (int i = 0; i < 5; i++) login("r@x.test", "wrong", null);
        assertEquals(429, login("r@x.test", "correct-horse", null));
    }

    @Test void secretsAreEncryptedAndBoundToOwner() {
        var sealed = box.seal("canary-value", "owner-1");
        assertFalse(new String(sealed.ciphertext()).contains("canary-value"));
        assertEquals("canary-value", box.open(sealed, "owner-1"));
        assertThrows(IllegalStateException.class, () -> box.open(sealed, "owner-2"));
        assertNotEquals(new String(box.seal("x", "o").wrappedKey()), new String(box.seal("x", "o").wrappedKey()));
    }
}
