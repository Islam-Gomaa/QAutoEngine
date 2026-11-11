package qaengine.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BaseUrlTest {

    @ParameterizedTest
    @CsvSource({
            "http://fixture.qa.internal:8090/, http://fixture.qa.internal:8090/",
            "HTTPS://Shop.Example.COM, https://shop.example.com/",
            "https://shop.example.com:443/app, https://shop.example.com/app",
            "http://shop.example.com:80//app//v2/, http://shop.example.com/app/v2/",
            "https://shop.example.com:8443/, https://shop.example.com:8443/",
            "'  http://localhost:8080  ', http://localhost:8080/",
            "https://xn--bcher-kva.example/, https://xn--bcher-kva.example/",
    })
    void acceptedUrlsAreNormalized(String raw, String expected) {
        assertEquals(expected, BaseUrl.normalize(raw));
    }

    @Test
    void normalizingIsStable() {
        String once = BaseUrl.normalize("HTTPS://Shop.Example.com:443//a");
        assertEquals(once, BaseUrl.normalize(once));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "shop.example.com", "/relative/path", "ftp://shop.example.com/", "file:///etc/passwd", "javascript:alert(1)",
            "data:text/html,x", "mailto:a@example.com", "ws://shop.example.com/", "http:shop.example.com",
            // user information
            "https://user:pass@shop.example.com/", "https://user@shop.example.com/", "https://shop.example.com@evil.example/",
            "https://a@b@evil.example/",
            // fragments and query strings
            "https://shop.example.com/#x", "https://shop.example.com/#", "https://shop.example.com/?token=1", "https://shop.example.com/?",
            // raw addresses in every spelling a browser accepts
            "http://127.0.0.1/", "http://10.0.0.5:8080/", "http://169.254.169.254/latest/meta-data/", "http://[::1]/", "http://[fd00::1]/",
            "http://2130706433/", "http://0x7f000001/", "http://0177.0.0.1/", "http://127.1/", "http://0x7f.0.0.1/", "http://shop.123/",
            // metadata names
            "http://metadata.google.internal/", "http://METADATA.google.internal/computeMetadata/v1/", "http://metadata/", "http://instance-data/",
            // malformed hosts and paths
            "https://shop.example.com./", "https://shop_example.com/", "https://-shop.example.com/", "https://shop..example.com/",
            "https://bücher.example/", "https://shop.example.com/a/../admin", "https://shop.example.com/a/%2e%2e/admin",
            "https://shop.example.com/a%2fb", "https://shop.example.com/a b", "https://shop.example.com\\@evil.example/",
            "https://shop.example.com/\u0000",
    })
    void unsafeOrMalformedUrlsAreRefused(String raw) {
        assertThrows(PolicyException.class, () -> BaseUrl.normalize(raw));
    }

    @Test
    void refusalsNeverRepeatTheInput() {
        String canary = "canary-secret-7Q";
        var e = assertThrows(PolicyException.class, () -> BaseUrl.normalize("https://u:" + canary + "@shop.example.com/"));
        assertFalse(e.getMessage().contains(canary));
    }

    @Test
    void tooLongIsRefused() {
        assertThrows(PolicyException.class, () -> BaseUrl.normalize("https://shop.example.com/" + "a".repeat(2100)));
    }

    @Test
    void bareHostsFollowTheSameRules() {
        assertEquals("imap.mail.example", BaseUrl.host("  IMAP.Mail.Example "));
        for (String bad : new String[] {"", "10.0.0.1", "[::1]", "::1", "2130706433", "metadata.google.internal", "mail.example.", "mail_example.com", "mail.example:993", "imap.123"}) {
            assertThrows(PolicyException.class, () -> BaseUrl.host(bad), bad);
        }
    }

    @Test
    void originDropsThePathAndKeepsAnExplicitPort() {
        assertEquals("http://fixture.qa.internal:8090", BaseUrl.origin(BaseUrl.normalize("http://fixture.qa.internal:8090/x")));
        assertEquals("https://shop.example.com", BaseUrl.origin(BaseUrl.normalize("https://shop.example.com:443/x")));
    }
}
