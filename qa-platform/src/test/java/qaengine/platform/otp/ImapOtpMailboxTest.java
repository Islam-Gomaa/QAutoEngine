package qaengine.platform.otp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The IMAP reader against a real IMAP server. No real account is involved: GreenMail runs on loopback. */
class ImapOtpMailboxTest {

    static final String ADDRESS = "otp-tests@mail.example";
    static final String PASSWORD = "mailbox-secret";
    static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");
    /** Only for the test server's self-signed certificate. Production code has no way to set these. */
    static final Map<String, String> TRUST_TEST_SERVER = Map.of("mail.imaps.ssl.trust", "*", "mail.imaps.ssl.checkserveridentity", "false");

    GreenMail mail;
    GreenMailUser user;
    ImapOtpMailbox reader = new ImapOtpMailbox(TRUST_TEST_SERVER);

    @BeforeEach void start() {
        mail = new GreenMail(new ServerSetup[] {ServerSetupTest.SMTP.dynamicPort(), ServerSetupTest.IMAPS.dynamicPort(), ServerSetupTest.IMAP.dynamicPort()});
        mail.start();
        user = mail.setUser(ADDRESS, ADDRESS, PASSWORD);
    }

    @AfterEach void stop() {
        mail.stop();
    }

    void deliver(String from, String subject, String body, String type) throws Exception {
        MimeMessage m = new MimeMessage(GreenMailUtil.getSession(mail.getSmtp().getServerSetup()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(jakarta.mail.Message.RecipientType.TO, ADDRESS);
        m.setSubject(subject);
        m.setContent(body, type);
        m.saveChanges();
        user.deliver(m);
    }

    OtpMailbox.Request request(Instant since, String sender, String subject, Set<String> consumed) {
        return new OtpMailbox.Request("localhost", mail.getImaps().getPort(), "IMAPS", ADDRESS, PASSWORD, "INBOX", sender, subject, SIX_DIGITS, since, consumed);
    }

    OtpMailbox.Request now() {
        return request(Instant.now().minusSeconds(5), "", "", Set.of());
    }

    @Test void theCodeComesFromTheNewestMatchingEmail() throws Exception {
        deliver("noreply@shop.example", "Your sign-in code", "Your code is 111111", "text/plain");
        Thread.sleep(1100);
        deliver("noreply@shop.example", "Your sign-in code", "Your code is 482913. It expires in 5 minutes.", "text/plain");
        var r = assertInstanceOf(OtpMailbox.Found.class, reader.read(now()));
        assertEquals("482913", r.code());
        assertEquals(64, r.messageHash().length());
    }

    @Test void mailFromBeforeTheRequestIsNeverUsed() throws Exception {
        deliver("noreply@shop.example", "Your sign-in code", "Your code is 111111", "text/plain");
        // The request happened a minute after that email, beyond the clock allowance.
        assertInstanceOf(OtpMailbox.NotYet.class, reader.read(request(Instant.now().plusSeconds(60), "", "", Set.of())));
    }

    @Test void senderAndSubjectFiltersSkipUnrelatedMailEvenWhenNewer() throws Exception {
        deliver("noreply@shop.example", "Your sign-in code", "Your code is 482913", "text/plain");
        Thread.sleep(1100);
        deliver("friend@elsewhere.example", "Lunch at 123456?", "See you at 123456", "text/plain");
        var r = assertInstanceOf(OtpMailbox.Found.class, reader.read(request(Instant.now().minusSeconds(5), "shop.example", "sign-in code", Set.of())));
        assertEquals("482913", r.code());
        assertInstanceOf(OtpMailbox.NotYet.class, reader.read(request(Instant.now().minusSeconds(5), "nobody.example", "", Set.of())));
    }

    @Test void anEmailACodeWasAlreadyTakenFromIsNotUsedAgain() throws Exception {
        deliver("noreply@shop.example", "Your sign-in code", "Your code is 482913", "text/plain");
        var first = assertInstanceOf(OtpMailbox.Found.class, reader.read(now()));
        assertInstanceOf(OtpMailbox.NotYet.class, reader.read(request(Instant.now().minusSeconds(5), "", "", Set.of(first.messageHash()))));
        Thread.sleep(1100);
        deliver("noreply@shop.example", "Your sign-in code", "Your code is 557811", "text/plain");
        var second = assertInstanceOf(OtpMailbox.Found.class, reader.read(request(Instant.now().minusSeconds(5), "", "", Set.of(first.messageHash()))));
        assertEquals("557811", second.code());
        assertNotEquals(first.messageHash(), second.messageHash());
    }

    @Test void aMatchingEmailWithoutACodeIsReportedRatherThanWaitedOn() throws Exception {
        deliver("noreply@shop.example", "Your sign-in code", "Click the link to sign in.", "text/plain");
        var r = assertInstanceOf(OtpMailbox.Unreadable.class, reader.read(now()));
        assertEquals("A matching email arrived but the code pattern found nothing in it", r.detail());
    }

    @Test void aCodeInAnHtmlOnlyEmailIsFoundInItsText() throws Exception {
        deliver("noreply@shop.example", "Your sign-in code", "<html><style>.x{color:#123456}</style><body><p>Your code: <b>904417</b></p></body></html>", "text/html");
        assertEquals("904417", assertInstanceOf(OtpMailbox.Found.class, reader.read(now())).code());
    }

    @Test void aWrongMailboxPasswordOrFolderFailsWithFixedText() {
        var wrong = new OtpMailbox.Request("localhost", mail.getImaps().getPort(), "IMAPS", ADDRESS, "wrong", "INBOX", "", "", SIX_DIGITS, Instant.now(), Set.of());
        assertEquals("The mail server refused the mailbox sign-in", assertInstanceOf(OtpMailbox.Failed.class, reader.read(wrong)).detail());
        var folder = new OtpMailbox.Request("localhost", mail.getImaps().getPort(), "IMAPS", ADDRESS, PASSWORD, "Missing", "", "", SIX_DIGITS, Instant.now(), Set.of());
        assertEquals("The mailbox folder does not exist", assertInstanceOf(OtpMailbox.Failed.class, reader.read(folder)).detail());
    }

    @Test void starttlsIsRequiredSoAPasswordIsNeverSentInTheClear() {
        // Plain IMAP without STARTTLS: the reader refuses to sign in rather than fall back to clear text.
        var plain = new OtpMailbox.Request("localhost", mail.getImap().getPort(), "STARTTLS", ADDRESS, PASSWORD, "INBOX", "", "", SIX_DIGITS, Instant.now(), Set.of());
        assertInstanceOf(OtpMailbox.Failed.class, reader.read(plain));
    }

    @Test void theProductionReaderDoesNotTrustAnUnknownCertificate() throws Exception {
        deliver("noreply@shop.example", "Your sign-in code", "Your code is 482913", "text/plain");
        assertInstanceOf(OtpMailbox.Failed.class, new ImapOtpMailbox().read(now()));
    }
}
