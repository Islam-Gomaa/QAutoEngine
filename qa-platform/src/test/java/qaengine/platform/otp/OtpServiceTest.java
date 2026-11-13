package qaengine.platform.otp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import qaengine.platform.IntegrationTestBase;
import qaengine.platform.credentials.CredentialService;
import qaengine.platform.identity.SecretStore;
import qaengine.platform.shared.Principal;

/**
 * Milestone 4 on the platform: codes from a real IMAP server (GreenMail on loopback), codes typed by a
 * person, the fallback from one to the other, and every limit on both.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"qa.otp-mail.allowed-hosts=localhost", "qa.otp-mail.private-hosts=localhost"})
class OtpServiceTest extends IntegrationTestBase {

    static final String ADDRESS = "otp-tests@mail.example";
    static GreenMail mail;
    static GreenMailUser inbox;
    static final AtomicInteger reads = new AtomicInteger();

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry r) throws Exception {
        Path f = Files.createTempFile("mk", ".key");
        Files.writeString(f, Base64.getEncoder().encodeToString(new byte[32]));
        r.add("qa.master-key-file", f::toString);
    }

    /** The real reader, trusting only the test server's certificate, with port 993 mapped to the test server's port. */
    @TestConfiguration
    static class TestMailbox {
        @Bean @Primary OtpMailbox testMailbox() {
            var real = new ImapOtpMailbox(ImapOtpMailboxTest.TRUST_TEST_SERVER);
            return r -> {
                reads.incrementAndGet();
                return real.read(new OtpMailbox.Request(r.host(), mail.getImaps().getPort(), r.security(), r.username(), r.password(), r.folder(), r.senderFilter(),
                        r.subjectFilter(), r.codePattern(), r.since(), r.consumed()));
            };
        }
    }

    @BeforeAll static void startMail() {
        mail = new GreenMail(new ServerSetup[] {ServerSetupTest.SMTP.dynamicPort(), ServerSetupTest.IMAPS.dynamicPort()});
        mail.start();
        inbox = mail.setUser(ADDRESS, ADDRESS, "mailbox-pw");
    }

    @AfterAll static void stopMail() {
        mail.stop();
    }

    @Autowired OtpService otps;
    @Autowired JdbcClient db;
    @Autowired SecretStore box;
    @Autowired CredentialService credentials;
    @Autowired MockMvc mvc;

    UUID org, project, env, profile, run;
    Principal starter, otherTester, admin, viewer, outsider;

    Principal user(UUID o, String role) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?,?, 'x')").params(id, id + "@x.test").update();
        db.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (?,?,?)").params(o, id, role).update();
        return new Principal(id, o, id + "@x.test", role.equals("ORGANIZATION_ADMIN"));
    }

    void member(Principal p, String role) {
        db.sql("INSERT INTO project_member (organization_id, project_id, user_id, role) VALUES (?,?,?,?)").params(org, project, p.userId(), role).update();
    }

    @BeforeEach void seed() throws Exception {
        mail.purgeEmailFromAllMailboxes();
        reads.set(0);
        org = UUID.randomUUID(); project = UUID.randomUUID(); env = UUID.randomUUID(); profile = UUID.randomUUID(); run = UUID.randomUUID();
        UUID other = UUID.randomUUID(), target = UUID.randomUUID();
        db.sql("INSERT INTO organization (id, name) VALUES (?, 'a'), (?, 'b')").params(org, other).update();
        admin = user(org, "MEMBER"); starter = user(org, "MEMBER"); otherTester = user(org, "MEMBER"); viewer = user(org, "MEMBER"); outsider = user(other, "ORGANIZATION_ADMIN");
        db.sql("INSERT INTO project (organization_id, id, name) VALUES (?,?, 'p')").params(org, project).update();
        member(admin, "PROJECT_ADMIN"); member(starter, "TESTER"); member(otherTester, "TESTER"); member(viewer, "VIEWER");
        db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?, 't')").params(org, target, project).update();
        db.sql("INSERT INTO environment (organization_id, id, target_id, name, classification, base_url) VALUES (?,?,?, 'e', 'LOCAL', 'http://fixture.qa.internal:8090/')").params(org, env, target).update();
        UUID cred = credentials.store(org, project, env, "acct", new CredentialService.Material("u", "p", null, null, null, null));
        db.sql("""
                INSERT INTO auth_profile (organization_id, id, environment_id, name, method, credential_id, is_default, submit_path, success_path, otp_submit_path)
                VALUES (?,?,?, 'Default', 'PASSWORD_EMAIL_OTP', ?, true, '/app/session-otp', '/app/home', '/app/otp-verify')
                """).params(org, profile, env, cred).update();
        db.sql("INSERT INTO test_run (organization_id, id, project_id, environment_id, seed, idempotency_key, created_by, auth_profile_id, kind) VALUES (?,?,?,?, 1, ?, ?, ?, 'AUTH_TEST')")
                .params(org, run, project, env, UUID.randomUUID().toString(), starter.userId(), profile).update();
    }

    void mailbox(String host, int timeout) {
        var sealed = box.seal("mailbox-pw", "mailbox/" + org + "/" + profile);
        db.sql("""
                INSERT INTO otp_mailbox (organization_id, auth_profile_id, address, host, security, port, sender_filter, subject_filter, code_pattern, timeout_seconds, poll_seconds,
                                         wrapped_key, ciphertext, dedicated_confirmed_by, dedicated_confirmed_at)
                VALUES (?,?,?,?, 'IMAPS', 993, 'shop.example', 'code', '\\b(\\d{6})\\b', ?, 2, ?,?, 'admin', now())
                """).params(org, profile, ADDRESS, host, timeout, sealed.wrappedKey(), sealed.ciphertext()).update();
    }

    void email(String subject, String body) throws Exception {
        MimeMessage m = new MimeMessage(GreenMailUtil.getSession(mail.getSmtp().getServerSetup()));
        m.setFrom(new InternetAddress("noreply@shop.example"));
        m.setRecipients(jakarta.mail.Message.RecipientType.TO, ADDRESS);
        m.setSubject(subject);
        m.setText(body);
        m.saveChanges();
        inbox.deliver(m);
    }

    void waitingForCode() {
        db.sql("INSERT INTO auth_attempt (organization_id, id, run_id, attempt, environment_id, auth_profile_id, status, stage) VALUES (?,?,?, 1, ?, ?, 'WAITING_FOR_OTP', 'OTP')")
                .params(org, UUID.randomUUID(), run, env, profile).update();
    }

    int type(Principal p, String code) throws Exception {
        return mvc.perform(post("/api/v1/projects/" + project + "/runs/" + run + "/otp").with(csrf())
                        .with(authentication(new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    void movedBack(int seconds) {
        db.sql("UPDATE otp_request SET requested_at = requested_at - make_interval(secs => ?), mailbox_checked_at = mailbox_checked_at - make_interval(secs => ?) WHERE run_id = ?")
                .params(seconds, seconds, run).update();
    }

    // ---- from the mailbox

    @Test void aCodeEmailedAfterTheRequestIsReleasedOnceAndNeverAgain() throws Exception {
        mailbox("localhost", 120);
        otps.requested(org, run, 1);
        var nothing = otps.poll(org, run, 1);
        assertNull(nothing.code());
        assertFalse(nothing.waitingForPerson());
        assertEquals(2, nothing.retryAfterSeconds());
        email("Your sign-in code", "Use 482913 to sign in.");
        movedBack(3); // past the polling interval
        assertEquals("482913", otps.poll(org, run, 1).code());
        // A second sign-in a moment later must not reuse the same email, even within the clock allowance.
        otps.requested(org, run, 1);
        assertNull(otps.poll(org, run, 1).code());
        assertEquals(1L, db.sql("SELECT count(*) FROM otp_consumed_message WHERE auth_profile_id = ?").param(profile).query(Long.class).single());
    }

    @Test void theMailboxIsReadNoMoreOftenThanItsPollingInterval() {
        mailbox("localhost", 120);
        otps.requested(org, run, 1);
        otps.poll(org, run, 1);
        otps.poll(org, run, 1);
        otps.poll(org, run, 1);
        assertEquals(1, reads.get());
    }

    @Test void anEmailWithoutACodeEndsTheWaitWithItsReason() throws Exception {
        mailbox("localhost", 120);
        otps.requested(org, run, 1);
        email("Your sign-in code", "Click the link we sent you.");
        var r = otps.poll(org, run, 1);
        assertEquals("OTP_EXTRACTION_FAILED", r.failure());
    }

    @Test void noEmailWithinTheMailboxTimeoutIsReported() {
        mailbox("localhost", 30);
        otps.requested(org, run, 1);
        movedBack(31);
        var r = otps.poll(org, run, 1);
        assertEquals("OTP_NOT_RECEIVED", r.failure());
        assertEquals("No matching email arrived within 30 seconds", r.detail());
    }

    // ---- from a person

    @Test void whenMailReadingIsNotEnabledAPersonTypesTheCodeIntoTheSameWaitingRun() throws Exception {
        mailbox("imap.mail.example", 120); // not on this platform's list of mail servers
        waitingForCode();
        otps.requested(org, run, 1);
        var waiting = otps.poll(org, run, 1);
        assertTrue(waiting.waitingForPerson());
        assertEquals("Mailbox reading is not enabled for this mail server. Waiting for someone to enter the code", waiting.detail());
        assertEquals(0, reads.get(), "the platform never connected");

        assertEquals(202, type(starter, " 735102 "));
        assertEquals("735102", otps.poll(org, run, 1).code());
        assertNull(otps.poll(org, run, 1).code(), "used once");
        assertEquals(0L, db.sql("SELECT count(*) FROM otp_submission WHERE run_id = ?").param(run).query(Long.class).single());
    }

    @Test void withoutAMailboxAPersonHasFiveMinutes() {
        otps.requested(org, run, 1);
        assertTrue(otps.poll(org, run, 1).waitingForPerson());
        movedBack(301);
        var r = otps.poll(org, run, 1);
        assertEquals("OTP_NOT_RECEIVED", r.failure());
        assertEquals("No code was entered within 5 minutes", r.detail());
    }

    @Test void onlyWhoeverStartedTheRunOrAProjectAdminTypesTheCodeAndOnlyWhileItWaits() throws Exception {
        otps.requested(org, run, 1);
        assertEquals(409, type(starter, "735102"), "not waiting yet");
        waitingForCode();
        assertEquals(403, type(otherTester, "735102"));
        assertEquals(403, type(viewer, "735102"));
        assertEquals(404, type(outsider, "735102"));
        assertEquals(409, type(starter, "12"), "too short");
        assertEquals(409, type(starter, "73510<script>"));
        assertEquals(202, type(admin, "735102"));
        db.sql("UPDATE test_run SET finished_at = now() WHERE id = ?").param(run).update();
        assertEquals(409, type(starter, "735103"), "a finished run waits for nothing");
    }

    @Test void aTypedCodeIsSealedAndAppearsNowhereInTheClear() throws Exception {
        otps.requested(org, run, 1);
        waitingForCode();
        String code = "AB12CD34";
        assertEquals(202, type(starter, code));
        for (String table : List.of("otp_submission", "otp_request", "audit_event", "auth_attempt")) {
            String rows = String.join("\n", db.sql("SELECT row_to_json(x)::text FROM " + table + " x WHERE organization_id = ?").param(org).query(String.class).list());
            assertFalse(rows.contains(code), table);
            assertFalse(rows.contains(java.util.HexFormat.of().formatHex(code.getBytes())), table + " as bytes");
        }
        // A new request makes an earlier typed code stale.
        otps.requested(org, run, 1);
        assertNull(otps.poll(org, run, 1).code());
    }
}
