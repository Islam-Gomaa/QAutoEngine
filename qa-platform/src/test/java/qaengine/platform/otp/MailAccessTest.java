package qaengine.platform.otp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MailAccessTest {

    @Test void mailReadingIsOffUntilADeploymentNamesItsMailServers() {
        assertEquals(Optional.of("Mailbox reading is not enabled for this mail server"), new MailAccess("", "").refusal("imap.gmail.com"));
        assertEquals(Optional.of("Mailbox reading is not enabled for this mail server"), new MailAccess("imap.mail.example", "").refusal("imap.other.example"));
    }

    @Test void anAllowedNameThatResolvesToAPrivateAddressIsRefusedUnlessApproved() {
        assertEquals(Optional.of("The mail server resolves to a private address"), new MailAccess("localhost", "").refusal("localhost"));
        assertEquals(Optional.empty(), new MailAccess("localhost", "localhost").refusal("LOCALHOST"));
    }

    @Test void privateRangesAreRecognized() throws Exception {
        for (String a : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "198.18.0.1", "::1", "fd00::1", "fe80::1"}) {
            assertTrue(MailAccess.isPrivate(InetAddress.getByName(a)), a);
        }
        for (String a : new String[] {"8.8.8.8", "142.250.1.1", "2001:4860:4860::8888"}) assertFalse(MailAccess.isPrivate(InetAddress.getByName(a)), a);
    }
}
