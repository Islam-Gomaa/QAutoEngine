package qaengine.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import qaengine.platform.credentials.CredentialService;
import qaengine.platform.identity.SecretStore;
import qaengine.platform.shared.Principal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Milestone 2: credentials bound to one environment, lifecycle without erasing secrets, sign-in
 * profiles and their mailbox settings, and that no value ever comes back out.
 */
@AutoConfigureMockMvc
class CredentialSecurityTest extends IntegrationTestBase {

    static final String FIXTURE = "http://fixture.qa.internal:8090/";

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry r) throws Exception {
        Path f = Files.createTempFile("mk", ".key");
        Files.writeString(f, Base64.getEncoder().encodeToString(new byte[32]));
        r.add("qa.master-key-file", f::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;
    @Autowired CredentialService credentials;
    @Autowired SecretStore secrets;

    UUID org, project;
    Principal admin, projectAdmin, tester, outsider;
    final String canary = "canary-" + UUID.randomUUID();

    Principal user(UUID o, String role) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?,?, 'x')").params(id, id + "@x.test").update();
        db.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (?,?,?)").params(o, id, role).update();
        return new Principal(id, o, id + "@x.test", role.equals("ORGANIZATION_ADMIN"));
    }

    @BeforeEach
    void seed() throws Exception {
        org = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        db.sql("INSERT INTO organization (id, name) VALUES (?, 'A'), (?, 'B')").params(org, other).update();
        admin = user(org, "ORGANIZATION_ADMIN");
        projectAdmin = user(org, "MEMBER");
        tester = user(org, "MEMBER");
        outsider = user(other, "ORGANIZATION_ADMIN");
        project = UUID.fromString(ok(admin, post("/api/v1/projects"), Map.of("name", "P " + UUID.randomUUID())).get("id").asString());
        for (var m : List.of(Map.entry(projectAdmin, "PROJECT_ADMIN"), Map.entry(tester, "TESTER"))) {
            db.sql("INSERT INTO project_member (organization_id, project_id, user_id, role) VALUES (?,?,?,?)").params(org, project, m.getKey().userId(), m.getValue()).update();
        }
    }

    MockHttpServletResponse call(Principal p, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.with(authentication(new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))))).with(csrf());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        MockHttpServletResponse r = mvc.perform(request).andReturn().getResponse();
        assertFalse(r.getContentAsString().contains(canary), () -> "a secret came back from " + request);
        return r;
    }

    JsonNode ok(Principal p, MockHttpServletRequestBuilder request, Object body) throws Exception {
        var r = call(p, request, body);
        assertTrue(r.getStatus() < 300, () -> "status " + r.getStatus() + " " + text(r));
        return r.getContentAsString().isEmpty() ? null : json.readTree(r.getContentAsString());
    }

    static String text(MockHttpServletResponse r) {
        try {
            return r.getContentAsString();
        } catch (Exception e) {
            return "";
        }
    }

    int status(Principal p, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(p, request, body).getStatus();
    }

    String detail(Principal p, MockHttpServletRequestBuilder request, Object body) throws Exception {
        var r = call(p, request, body);
        assertEquals(409, r.getStatus(), () -> text(r));
        return json.readTree(r.getContentAsString()).get("detail").asString();
    }

    String base() {
        return "/api/v1/projects/" + project;
    }

    UUID environment(String url, String classification) throws Exception {
        String target = ok(admin, post(base() + "/targets"), Map.of("name", "App " + UUID.randomUUID(), "type", "WEB")).get("id").asString();
        return UUID.fromString(ok(admin, post(base() + "/targets/" + target + "/environments"), Map.of("name", "Env", "classification", classification, "baseUrl", url)).get("id").asString());
    }

    UUID credential(UUID environment, String password) throws Exception {
        var body = new HashMap<String, Object>(Map.of("name", "c " + UUID.randomUUID(), "username", "fixture-user", "password", password));
        if (environment != null) body.put("environmentId", environment.toString());
        return UUID.fromString(ok(admin, post(base() + "/credentials"), body).get("id").asString());
    }

    int login(UUID environment, UUID credential) throws Exception {
        return status(admin, put(base() + "/environments/" + environment + "/login"),
                Map.of("method", "PASSWORD", "credentialId", credential.toString(), "loginPath", "/login", "submitPath", "/session", "successPath", "/account"));
    }

    JsonNode credentialView(UUID id) throws Exception {
        for (JsonNode c : ok(admin, get(base() + "/credentials"), null)) if (c.get("id").asString().equals(id.toString())) return c;
        throw new AssertionError("credential not listed");
    }

    JsonNode profile(UUID id) throws Exception {
        for (JsonNode c : ok(admin, get(base() + "/auth-profiles"), null)) if (c.get("id").asString().equals(id.toString())) return c;
        throw new AssertionError("profile not listed");
    }

    int run(UUID environment) throws Exception {
        return status(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), Map.of("environmentId", environment.toString()));
    }

    String runRefusal(UUID environment) throws Exception {
        return detail(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), Map.of("environmentId", environment.toString()));
    }

    Map<String, Object> mailbox(Object... overrides) {
        var m = new HashMap<String, Object>(Map.of("address", "otp-tests@mail.example", "host", "imap.mail.example", "security", "IMAPS",
                "password", "mailbox-" + canary, "codePattern", "\\b(\\d{6})\\b", "dedicatedMailboxConfirmed", true));
        for (int i = 0; i < overrides.length; i += 2) {
            if (overrides[i + 1] == null) m.remove((String) overrides[i]);
            else m.put((String) overrides[i], overrides[i + 1]);
        }
        return m;
    }

    Map<String, Object> otpProfile(UUID credential, Map<String, Object> mailbox) {
        var p = new HashMap<String, Object>(Map.of("name", "OTP", "method", "PASSWORD_EMAIL_OTP", "credentialId", credential.toString(), "successPath", "/account"));
        if (mailbox != null) p.put("mailbox", mailbox);
        return p;
    }

    // ---- binding

    @Test
    void aCredentialIsBoundToTheFirstEnvironmentThatUsesItAndRefusedElsewhere() throws Exception {
        UUID staging = environment("https://staging.shop.example/", "STAGING");
        UUID uat = environment("https://uat.shop.example/", "UAT");
        UUID loose = credential(null, canary);
        assertNull(credentialView(loose).get("environmentId").asString(null), "not bound until used");
        assertEquals(200, login(staging, loose));
        assertEquals(staging.toString(), credentialView(loose).get("environmentId").asString());
        assertEquals("This credential belongs to another environment", detail(admin, put(base() + "/environments/" + uat + "/login"),
                Map.of("method", "PASSWORD", "credentialId", loose.toString(), "successPath", "/account")));
        assertEquals("This credential belongs to another environment", detail(admin, post(base() + "/environments/" + uat + "/auth-profiles"),
                Map.of("name", "Second", "method", "PASSWORD", "credentialId", loose.toString())));

        UUID forUat = credential(uat, canary);
        assertEquals("This credential belongs to another environment", detail(admin, post(base() + "/environments/" + staging + "/auth-profiles"),
                Map.of("name", "Borrowed", "method", "PASSWORD", "credentialId", forUat.toString())));
        assertEquals(1L, db.sql("SELECT count(*) FROM audit_event WHERE organization_id = ? AND action = 'CREDENTIAL_BOUND'").param(org).query(Long.class).single());
    }

    @Test
    void aCredentialCannotBeCreatedForAnEnvironmentOutsideTheProject() throws Exception {
        UUID otherProject = UUID.fromString(ok(admin, post("/api/v1/projects"), Map.of("name", "Q " + UUID.randomUUID())).get("id").asString());
        String target = ok(admin, post("/api/v1/projects/" + otherProject + "/targets"), Map.of("name", "T", "type", "WEB")).get("id").asString();
        String foreign = ok(admin, post("/api/v1/projects/" + otherProject + "/targets/" + target + "/environments"),
                Map.of("name", "E", "classification", "QA", "baseUrl", "https://q.shop.example/")).get("id").asString();
        assertEquals(404, status(admin, post(base() + "/credentials"), Map.of("name", "x", "password", canary, "environmentId", foreign)));
    }

    @Test
    void sharedCredentialsFromBeforeBindingKeepWorkingAndNoCredentialCanBecomeShared() throws Exception {
        UUID a = environment("https://a.shop.example/", "QA");
        UUID b = environment("https://b.shop.example/", "QA");
        UUID legacy = credential(null, canary);
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> db.sql("UPDATE credential SET shared = true WHERE organization_id = ? AND id = ?").params(org, legacy).update(), "only the V8 migration may mark one shared");
        // Stand in for the migration, which marks credentials that several environments already used.
        db.sql("ALTER TABLE credential DISABLE TRIGGER credential_shared_guard").update();
        try {
            db.sql("UPDATE credential SET shared = true WHERE organization_id = ? AND id = ?").params(org, legacy).update();
        } finally {
            db.sql("ALTER TABLE credential ENABLE TRIGGER credential_shared_guard").update();
        }
        assertEquals(200, login(a, legacy));
        assertEquals(200, login(b, legacy));
        JsonNode view = credentialView(legacy);
        assertTrue(view.get("shared").asBoolean());
        assertTrue(view.get("environmentId").isNull());
        assertEquals(2, view.get("usedBy").size());
    }

    // ---- lifecycle

    @Test
    void updatesKeepEveryValueThatIsNotGiven() throws Exception {
        UUID env = environment(FIXTURE, "QA");
        UUID id = credential(env, canary);
        JsonNode created = credentialView(id);
        assertEquals("f•••r", created.get("usernameHint").asString());
        assertEquals(List.of("username", "password"), List.of(created.get("storedFields").get(0).asString(), created.get("storedFields").get(1).asString()));

        ok(admin, put(base() + "/credentials/" + id), Map.of("name", "Renamed", "password", "", "username", "  "));
        assertEquals(canary, credentials.release(org, id).password(), "a blank password keeps the stored one");
        assertEquals("fixture-user", credentials.release(org, id).username());
        assertEquals("Renamed", credentialView(id).get("name").asString());

        String rotated = "rotated-" + canary;
        ok(admin, put(base() + "/credentials/" + id), Map.of("password", rotated));
        assertEquals(rotated, credentials.release(org, id).password());
        assertEquals("fixture-user", credentials.release(org, id).username(), "replacing the password keeps the username");
        List<String> details = db.sql("SELECT detail FROM audit_event WHERE organization_id = ? AND action = 'CREDENTIAL_UPDATED' ORDER BY id").param(org).query(String.class).list();
        assertEquals(List.of("no values replaced", "replaced password"), details);
    }

    @Test
    void disabledCredentialsAndProfilesRefuseRunsUntilEnabled() throws Exception {
        UUID env = environment(FIXTURE, "QA");
        UUID cred = credential(env, canary);
        assertEquals(200, login(env, cred));
        assertEquals(201, run(env));
        UUID profile = UUID.fromString(ok(admin, get(base() + "/auth-profiles"), null).get(0).get("id").asString());

        assertFalse(ok(admin, post(base() + "/credentials/" + cred + "/disable"), null).get("enabled").asBoolean());
        assertEquals("The sign-in profile's credential is disabled", runRefusal(env));
        ok(admin, post(base() + "/credentials/" + cred + "/enable"), null);
        assertFalse(ok(admin, post(base() + "/auth-profiles/" + profile + "/disable"), null).get("enabled").asBoolean());
        assertEquals("The environment's sign-in profile is disabled", runRefusal(env));
        ok(admin, post(base() + "/auth-profiles/" + profile + "/enable"), null);
        assertEquals(201, run(env));
    }

    @Test
    void credentialsInUseCannotBeDeletedAndProfilesInUseByARunCannotEither() throws Exception {
        UUID env = environment(FIXTURE, "QA");
        UUID cred = credential(env, canary);
        assertEquals(200, login(env, cred));
        assertEquals("This credential is used by a login profile", detail(admin, delete(base() + "/credentials/" + cred), null));
        assertEquals(201, run(env));
        UUID profile = UUID.fromString(ok(admin, get(base() + "/auth-profiles"), null).get(0).get("id").asString());
        assertEquals("A run that has not finished uses this sign-in profile", detail(admin, delete(base() + "/auth-profiles/" + profile), null));
        db.sql("UPDATE test_run SET finished_at = now(), status = 'SUCCEEDED' WHERE organization_id = ?").param(org).update();
        ok(admin, delete(base() + "/auth-profiles/" + profile), null);
        assertEquals(1L, db.sql("SELECT count(*) FROM test_run WHERE organization_id = ? AND auth_profile_id IS NULL").param(org).query(Long.class).single(), "history stays, the link goes");
        ok(admin, delete(base() + "/credentials/" + cred), null);
    }

    // ---- profiles

    @Test
    void aRunIsPinnedToTheProfileThatWasDefaultWhenItWasCreated() throws Exception {
        UUID env = environment(FIXTURE, "QA");
        assertEquals(200, login(env, credential(env, canary)));
        assertEquals(201, run(env));
        UUID first = db.sql("SELECT auth_profile_id FROM test_run WHERE organization_id = ?").param(org).query(UUID.class).single();
        JsonNode second = ok(admin, post(base() + "/environments/" + env + "/auth-profiles"),
                Map.of("name", "Second", "method", "PASSWORD", "credentialId", credential(env, canary).toString(), "successPath", "/account", "submitPath", "/session", "isDefault", true));
        assertTrue(second.get("isDefault").asBoolean());
        assertFalse(profile(first).get("isDefault").asBoolean(), "one default per environment");
        assertEquals(first, db.sql("SELECT auth_profile_id FROM test_run WHERE organization_id = ?").param(org).query(UUID.class).single());
        assertEquals(201, run(env));
        assertEquals(1L, db.sql("SELECT count(*) FROM test_run WHERE organization_id = ? AND auth_profile_id = ?").params(org, UUID.fromString(second.get("id").asString())).query(Long.class).single());

        assertEquals(409, status(admin, delete(base() + "/auth-profiles/" + second.get("id").asString()), null), "its run is still open");
        db.sql("UPDATE test_run SET finished_at = now(), status = 'SUCCEEDED' WHERE organization_id = ?").param(org).update();
        ok(admin, delete(base() + "/auth-profiles/" + second.get("id").asString()), null);
        assertTrue(profile(first).get("isDefault").asBoolean(), "deleting the default hands over to the remaining profile");
    }

    @Test
    void anUnfinishedLoginFlowRefusesRunsAndTheOriginalFormStillNeedsASuccessPath() throws Exception {
        UUID env = environment(FIXTURE, "QA");
        UUID cred = credential(env, canary);
        JsonNode created = ok(admin, post(base() + "/environments/" + env + "/auth-profiles"), Map.of("name", "Draft", "method", "PASSWORD", "credentialId", cred.toString()));
        assertFalse(created.get("loginFlowComplete").asBoolean());
        assertTrue(created.get("isDefault").asBoolean(), "the first profile becomes the default");
        assertEquals("Finish the sign-in profile's login flow before running", runRefusal(env));
        assertEquals("A web login needs a success path", detail(admin, put(base() + "/environments/" + env + "/login"), Map.of("method", "PASSWORD", "credentialId", cred.toString())));
        ok(admin, put(base() + "/auth-profiles/" + created.get("id").asString()), Map.of("name", "Draft", "method", "PASSWORD", "successPath", "/account"));
        assertEquals("Finish the sign-in profile's login flow before running", runRefusal(env), "the gate needs the submit path to let the sign-in through");
        ok(admin, put(base() + "/auth-profiles/" + created.get("id").asString()), Map.of("name", "Draft", "method", "PASSWORD", "successPath", "/account", "submitPath", "/session"));
        assertEquals(201, run(env));
        assertEquals("Login paths must be relative paths", detail(admin, put(base() + "/auth-profiles/" + created.get("id").asString()),
                Map.of("name", "Draft", "method", "PASSWORD", "successPath", "https://evil.example/account")));
    }

    static List<Object[]> badMailboxes() {
        return List.of(
                new Object[] {new Object[] {"host", "10.0.0.5"}, "Mail server: Use a host name, not an IP address"},
                new Object[] {new Object[] {"host", "metadata.google.internal"}, "Mail server: Cloud metadata hosts are not allowed"},
                new Object[] {new Object[] {"address", "not-an-address"}, "Enter the mailbox address"},
                new Object[] {new Object[] {"security", "PLAIN"}, "Mailbox security must be IMAPS or STARTTLS"},
                new Object[] {new Object[] {"codePattern", "(\\d{6}"}, "The code pattern is not a valid regular expression"},
                new Object[] {new Object[] {"codePattern", "(\\d{3})-(\\d{3})"}, "The code pattern may capture at most one group"},
                new Object[] {new Object[] {"timeoutSeconds", 30, "pollSeconds", 30}, "Mailbox timing is outside the allowed range"},
                new Object[] {new Object[] {"dedicatedMailboxConfirmed", false}, "Confirm this is a dedicated test mailbox you are authorized to use"},
                new Object[] {new Object[] {"password", null}, "Enter the mailbox password"},
                new Object[] {new Object[] {"subjectFilter", "Your code\nBcc: x"}, "Mailbox folder or filters are not valid"});
    }

    @ParameterizedTest
    @MethodSource("badMailboxes")
    void unsafeOrIncompleteMailboxSettingsAreRefusedAndNothingIsSaved(Object[] overrides, String reason) throws Exception {
        UUID env = environment(FIXTURE, "QA");
        UUID cred = credential(env, canary);
        assertEquals(reason, detail(admin, post(base() + "/environments/" + env + "/auth-profiles"), otpProfile(cred, mailbox(overrides))));
        assertEquals(0L, db.sql("SELECT count(*) FROM auth_profile WHERE organization_id = ?").param(org).query(Long.class).single());
    }

    @Test
    void mailboxSettingsAreStoredSealedAndEmailOtpNeedsItsCodeStepToRun() throws Exception {
        UUID env = environment(FIXTURE, "QA");
        UUID cred = credential(env, canary);
        // Without a mailbox, a person types the code: the profile is allowed, and runs once its code step is set.
        JsonNode manual = ok(admin, post(base() + "/environments/" + env + "/auth-profiles"), otpProfile(cred, null));
        assertTrue(manual.get("mailbox").isNull());
        ok(admin, delete(base() + "/auth-profiles/" + manual.get("id").asString()), null);
        assertEquals("A mailbox is only used with Email OTP", detail(admin, post(base() + "/environments/" + env + "/auth-profiles"),
                Map.of("name", "P", "method", "PASSWORD", "credentialId", cred.toString(), "mailbox", mailbox())));

        JsonNode created = ok(admin, post(base() + "/environments/" + env + "/auth-profiles"), otpProfile(cred, mailbox("host", "IMAP.Mail.Example")));
        UUID id = UUID.fromString(created.get("id").asString());
        JsonNode m = created.get("mailbox");
        assertEquals("imap.mail.example", m.get("host").asString());
        assertEquals(993, m.get("port").asInt());
        assertEquals("INBOX", m.get("folder").asString());
        assertEquals(admin.email(), m.get("dedicatedConfirmedBy").asString());
        assertFalse(m.has("password"));
        assertEquals("Finish the sign-in profile's login flow before running", runRefusal(env), "the code needs its own submit path through the gate");
        assertEquals("mailbox-" + canary, mailboxPassword(id));

        // A blank password keeps the sealed one; moving to another server needs the confirmation again.
        ok(admin, put(base() + "/auth-profiles/" + id), otpProfile(cred, mailbox("password", "", "security", "STARTTLS", "dedicatedMailboxConfirmed", null)));
        assertEquals("mailbox-" + canary, mailboxPassword(id));
        assertEquals(143, profile(id).get("mailbox").get("port").asInt());
        assertEquals("Confirm this is a dedicated test mailbox you are authorized to use",
                detail(admin, put(base() + "/auth-profiles/" + id), otpProfile(cred, mailbox("host", "imap.elsewhere.example", "password", "", "dedicatedMailboxConfirmed", null))));
        ok(admin, put(base() + "/auth-profiles/" + id), otpProfile(cred, mailbox("password", "new-mailbox-" + canary)));
        assertEquals("new-mailbox-" + canary, mailboxPassword(id));

        // Leaving Email OTP removes the mailbox and its sealed password.
        ok(admin, put(base() + "/auth-profiles/" + id), Map.of("name", "OTP", "method", "PASSWORD", "successPath", "/account", "submitPath", "/session"));
        assertEquals(0L, db.sql("SELECT count(*) FROM otp_mailbox WHERE organization_id = ?").param(org).query(Long.class).single());
        assertEquals(201, run(env));
    }

    String mailboxPassword(UUID profile) {
        var sealed = db.sql("SELECT wrapped_key, ciphertext FROM otp_mailbox WHERE organization_id = ? AND auth_profile_id = ?").params(org, profile)
                .query((rs, n) -> new SecretStore.Sealed(rs.getBytes(1), rs.getBytes(2))).single();
        assertThrows(IllegalStateException.class, () -> secrets.open(sealed, "mailbox/" + org + "/" + UUID.randomUUID()), "sealed to its own profile");
        return secrets.open(sealed, "mailbox/" + org + "/" + profile);
    }

    // ---- onboarding

    @Test
    void setupCreatesABoundCredentialAndDefaultProfileWithTheApplication() throws Exception {
        var auth = Map.of("method", "PASSWORD_EMAIL_OTP", "username", "qa-bot@shop.example", "password", canary, "mailbox", mailbox());
        String target = ok(admin, post(base() + "/targets"), Map.of("name", "Shop", "type", "WEB", "environments",
                List.of(Map.of("name", "Staging", "classification", "STAGING", "baseUrl", "https://staging.shop.example/", "authentication", auth)))).get("id").asString();
        JsonNode p = ok(admin, get(base() + "/auth-profiles"), null).get(0);
        assertEquals("Default", p.get("name").asString());
        assertTrue(p.get("isDefault").asBoolean());
        assertEquals("PASSWORD_EMAIL_OTP", p.get("method").asString());
        assertFalse(p.get("loginFlowComplete").asBoolean(), "the login flow is configured in a later step");
        assertEquals("q•••e", p.get("usernameHint").asString());
        JsonNode c = credentialView(UUID.fromString(p.get("credentialId").asString()));
        assertEquals("Shop / Staging / Default", c.get("name").asString());
        assertEquals(p.get("environmentId").asString(), c.get("environmentId").asString());
        assertEquals(target, db.sql("SELECT target_id FROM environment WHERE organization_id = ? AND id = ?").params(org, UUID.fromString(c.get("environmentId").asString())).query(UUID.class).single().toString());
    }

    @Test
    void setupRefusesUnsupportedSignInAndLeavesNothingBehind() throws Exception {
        var env = new HashMap<String, Object>(Map.of("name", "Staging", "classification", "STAGING", "baseUrl", "https://staging.shop.example/"));
        env.put("authentication", Map.of("method", "SESSION_IMPORT", "username", "u", "password", canary));
        assertEquals("Choose username and password, with or without email OTP", detail(admin, post(base() + "/targets"), Map.of("name", "Shop", "type", "WEB", "environments", List.of(env))));
        env.put("authentication", Map.of("method", "PASSWORD", "username", "u"));
        assertEquals("Enter the username and password", detail(admin, post(base() + "/targets"), Map.of("name", "Shop", "type", "WEB", "environments", List.of(env))));
        env.put("authentication", Map.of("method", "PASSWORD", "username", "u", "password", canary));
        assertEquals("Login method does not fit this channel", detail(admin, post(base() + "/targets"), Map.of("name", "Api", "type", "API", "environments", List.of(env))));
        for (String table : List.of("target", "credential", "auth_profile")) {
            assertEquals(0L, db.sql("SELECT count(*) FROM " + table + " WHERE organization_id = ?").param(org).query(Long.class).single(), table);
        }
    }

    // ---- login flow and connection tests (Milestone 3)

    static Map<String, Object> locator(String strategy, String value, String name) {
        var m = new HashMap<String, Object>(Map.of("strategy", strategy, "value", value));
        if (name != null) m.put("name", name);
        return m;
    }

    Map<String, Object> flow(Map<String, Object> locators, Object timeout) {
        var body = new HashMap<String, Object>(Map.of("name", "Flow", "method", "PASSWORD", "loginPath", "/app/login", "submitPath", "/app/session"));
        if (locators != null) body.put("locators", locators);
        if (timeout != null) body.put("timeoutSeconds", timeout);
        return body;
    }

    @Test
    void loginFlowLocatorsAreCheckedByTheRulesTheWorkerFollows() throws Exception {
        UUID env = environment(FIXTURE, "QA");
        UUID cred = credential(env, canary);
        String url = base() + "/environments/" + env + "/auth-profiles";
        java.util.function.Function<Map<String, Object>, Map<String, Object>> with = l -> { var b = flow(l, null); b.put("credentialId", cred.toString()); return b; };
        assertEquals("Unsupported locator strategy", detail(admin, post(url), with.apply(Map.of("success", locator("XPATH", "//div", null)))));
        assertEquals("Unsupported role for a locator", detail(admin, post(url), with.apply(Map.of("success", locator("ROLE", "banana", "x")))));
        assertEquals("Set both the password field and the sign-in button, or neither", detail(admin, post(url), with.apply(Map.of("password", locator("LABEL", "Password", null)))));
        var tooFast = flow(Map.of("success", locator("TEST_ID", "account-menu", null)), 2);
        tooFast.put("credentialId", cred.toString());
        assertEquals("The sign-in timeout must be between 5 and 60 seconds", detail(admin, post(url), tooFast));

        var good = flow(Map.of("username", locator("LABEL", " Email ", null), "password", locator("LABEL", "Password", null), "submit", locator("ROLE", "BUTTON", "Sign in"),
                "success", locator("TEST_ID", "account-menu", null), "failure", locator("ROLE", "alert", null)), 20);
        good.put("credentialId", cred.toString());
        JsonNode created = ok(admin, post(url), good);
        assertTrue(created.get("loginFlowComplete").asBoolean(), "a signed-in marker is a success check");
        assertEquals("Email", created.get("locators").get("username").get("value").asString());
        assertEquals("button", created.get("locators").get("submit").get("value").asString());
        assertEquals("Sign in", created.get("locators").get("submit").get("name").asString());
        assertEquals(20, created.get("timeoutSeconds").asInt());
        assertEquals(201, run(env));
        // The original form keeps the locators it does not know about.
        assertEquals(200, status(admin, put(base() + "/environments/" + env + "/login"), Map.of("method", "PASSWORD", "credentialId", cred.toString(), "submitPath", "/app/session", "successPath", "/app/home")));
        assertEquals("account-menu", profile(UUID.fromString(created.get("id").asString())).get("locators").get("success").get("value").asString());
    }

    UUID authTest(UUID environment, UUID profile) throws Exception {
        var body = new HashMap<String, Object>(Map.of("environmentId", environment.toString(), "kind", "AUTH_TEST"));
        if (profile != null) body.put("authProfileId", profile.toString());
        return UUID.fromString(ok(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), body).get("id").asString());
    }

    @Test
    void aConnectionTestIsAOneAttemptRunThroughTheSameGates() throws Exception {
        UUID env = environment(FIXTURE, "QA");
        UUID connectOnly = authTest(env, null);
        assertEquals("AUTH_TEST", db.sql("SELECT kind FROM test_run WHERE id = ?").param(connectOnly).query(String.class).single());
        assertTrue(db.sql("SELECT auth_profile_id IS NULL FROM test_run WHERE id = ?").param(connectOnly).query(Boolean.class).single(), "no profile: connection only");
        assertEquals(1, db.sql("SELECT max_attempts FROM run_job WHERE run_id = ?").param(connectOnly).query(Integer.class).single(), "a retry would spend another sign-in");
        assertEquals("AUTH_TEST", ok(tester, get(base() + "/runs/" + connectOnly), null).get("kind").asString());

        assertEquals(200, login(env, credential(env, canary)));
        UUID profile = UUID.fromString(ok(admin, get(base() + "/auth-profiles"), null).get(0).get("id").asString());
        UUID signIn = authTest(env, profile);
        assertEquals(profile, db.sql("SELECT auth_profile_id FROM test_run WHERE id = ?").param(signIn).query(UUID.class).single());
        JsonNode last = profile(profile).get("lastTest");
        assertEquals(signIn.toString(), last.get("runId").asString());
        assertEquals("QUEUED", last.get("runStatus").asString());
        assertTrue(last.get("status").isNull(), "nothing to report until the worker has run it");

        UUID otherEnv = environment("http://fixture.qa.internal:8090/other", "QA");
        assertEquals(404, status(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()),
                Map.of("environmentId", otherEnv.toString(), "kind", "AUTH_TEST", "authProfileId", profile.toString())), "a profile from another environment");
        assertEquals(409, status(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()),
                Map.of("environmentId", env.toString(), "kind", "EXPLORATION", "authProfileId", profile.toString())));
        assertEquals(409, status(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()), Map.of("environmentId", env.toString(), "kind", "SOMETHING")));
        UUID real = environment("https://staging.shop.example/", "STAGING");
        assertEquals("Real targets are locked until the pre-pilot security gate passes", detail(tester, post(base() + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()),
                Map.of("environmentId", real.toString(), "kind", "AUTH_TEST")));
    }

    // ---- access and leaks

    @Test
    void onlyProjectAdminsManageSignInAndProductionNeedsAnOrganizationAdmin() throws Exception {
        UUID env = environment(FIXTURE, "QA");
        UUID cred = credential(env, canary);
        var body = Map.of("name", "P", "method", "PASSWORD", "credentialId", cred.toString());
        assertEquals(403, status(tester, get(base() + "/auth-profiles"), null));
        assertEquals(403, status(tester, post(base() + "/environments/" + env + "/auth-profiles"), body));
        assertEquals(403, status(tester, post(base() + "/credentials/" + cred + "/disable"), null));
        assertEquals(404, status(outsider, get(base() + "/auth-profiles"), null));
        assertEquals(201, status(projectAdmin, post(base() + "/environments/" + env + "/auth-profiles"), body));

        String target = ok(admin, post(base() + "/targets"), Map.of("name", "Prod", "type", "WEB")).get("id").asString();
        UUID prod = UUID.fromString(ok(admin, post(base() + "/targets/" + target + "/environments"),
                Map.of("name", "Prod", "classification", "PRODUCTION", "baseUrl", "https://shop.example/", "includePaths", List.of("/catalog"))).get("id").asString());
        UUID prodCred = credential(prod, canary);
        assertEquals(403, status(projectAdmin, post(base() + "/environments/" + prod + "/auth-profiles"), Map.of("name", "P", "method", "PASSWORD", "credentialId", prodCred.toString())));
        assertEquals(201, status(admin, post(base() + "/environments/" + prod + "/auth-profiles"), Map.of("name", "P", "method", "PASSWORD", "credentialId", prodCred.toString())));
    }

    @Test
    void noValueIsStoredInTheClearOrWrittenToTheAuditLog() throws Exception {
        UUID env = environment(FIXTURE, "QA");
        UUID cred = credential(env, canary);
        ok(admin, put(base() + "/credentials/" + cred), Map.of("password", "second-" + canary, "totpSeed", Base64.getEncoder().encodeToString(("seed-" + canary).getBytes())));
        ok(admin, post(base() + "/environments/" + env + "/auth-profiles"), otpProfile(cred, mailbox()));
        // Every read path a person can reach. call() already fails on any response that contains the canary.
        for (String path : List.of("/credentials", "/auth-profiles", "/login-profiles", "/environments", "/targets")) ok(admin, get(base() + path), null);
        String hex = HexFormat.of().formatHex(canary.getBytes());
        for (String table : List.of("credential", "auth_profile", "otp_mailbox", "audit_event")) {
            String rows = String.join("\n", db.sql("SELECT row_to_json(x)::text FROM " + table + " x WHERE organization_id = ?").param(org).query(String.class).list());
            assertFalse(rows.isEmpty(), table);
            assertFalse(rows.contains(canary), table + " holds a value in the clear");
            assertFalse(rows.contains(hex), table + " holds a value as unencrypted bytes");
        }
    }
}
