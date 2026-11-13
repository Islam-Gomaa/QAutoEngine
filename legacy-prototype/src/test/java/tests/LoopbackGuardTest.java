package tests;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import models.LinkResult;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import utilities.DriverFactory;
import utilities.LoopbackGuard;
import validators.ApiValidator;
import validators.LinkValidator;

public class LoopbackGuardTest {

    @DataProvider
    public Object[][] refused() {
        return new Object[][] {
                {"https://example.com/"}, {"http://127.0.0.1.example.com/"}, {"http://localhost.example.com/"},
                {"http://example.com@evil.test/"}, {"ftp://127.0.0.1/"}, {"file:///etc/passwd"},
                {"mailto:a@b.test"}, {"javascript:void(0)"}, {""}, {null}, {"http://10.0.0.1/"}, {"http://169.254.169.254/"}
        };
    }

    @Test(dataProvider = "refused")
    public void refusesEverythingNotLoopback(String url) {
        assertFalse(LoopbackGuard.isLoopback(url));
        expectThrows(SecurityException.class, () -> LoopbackGuard.requireLoopback(url));
    }

    @Test
    public void allowsLoopback() {
        assertTrue(LoopbackGuard.isLoopback("http://127.0.0.1:8080/a"));
        assertTrue(LoopbackGuard.isLoopback("http://localhost/"));
        assertTrue(LoopbackGuard.isLoopback("http://[::1]:9/"));
    }

    @Test
    public void validatorsDoNotContactExternalHosts() {
        LinkResult link = LinkValidator.validate("https://example.invalid/x", "");
        assertEquals(link.result, "BLOCKED");
        assertEquals(LinkValidator.getStatusCode("https://example.invalid/x"), -1);
        assertEquals(ApiValidator.validate("https://example.invalid/api").result, "BLOCKED");
    }

    @Test
    public void firefoxRefusedBecauseItCannotBeContained() {
        expectThrows(SecurityException.class, () -> DriverFactory.createDriver("firefox"));
    }
}
