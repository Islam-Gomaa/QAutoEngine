package tests;

import io.qameta.allure.*;
import models.LinkResult;
import org.openqa.selenium.WebDriver;
import org.testng.Assert;
import org.testng.annotations.*;

import crawler.LinkCollector;
import utilities.ConfigReader;
import utilities.DriverFactory;
import validators.ApiValidator;
import validators.LinkValidator;

import java.time.Duration;
import java.util.List;

@Epic("Link Scanner")
@Feature("Validation")
public class LinkScannerTest {

    private WebDriver driver;

    // =========================
    // 🔥 SETUP
    // =========================
    @BeforeClass
    public void setup() {

        System.out.println("🚀 Starting Browser...");

        driver = DriverFactory.createDriver(ConfigReader.get("browser"));

        driver.manage().timeouts().implicitlyWait(
                Duration.ofSeconds(ConfigReader.getInt("timeout", 10))
        );

        String url = ConfigReader.get("url");

        System.out.println("🌍 Opening: " + url);

        driver.get(url);
    }

    // =========================
    // 🔥 DATA PROVIDER
    // =========================
    @DataProvider(name = "links", parallel = true)
    public Object[][] links() {

        System.out.println("🔍 Collecting links...");

        List<String> links = LinkCollector.collect(driver);

        if (links == null || links.isEmpty()) {
            throw new RuntimeException("❌ No links collected");
        }

        System.out.println("🔗 Total links found: " + links.size());

        return links.stream()
                .distinct()
                .map(link -> new Object[]{link})
                .toArray(Object[][]::new);
    }

    // =========================
    // 🔥 TEST
    // =========================
    @Test(dataProvider = "links")
    @Story("Validate Links")
    @Severity(SeverityLevel.CRITICAL)
    public void validateLink(String href) {

        System.out.println("🔗 Checking: " + href);
        Allure.step("🔗 Checking link: " + href);

        LinkResult result;

        try {

            result = isApi(href)
                    ? validateApi(href)
                    : validateUi(href);

        } catch (Exception e) {

            result = new LinkResult(href, "", -1, 0, "ERROR");

            System.out.println("❌ Exception: " + e.getMessage());
            Allure.step("❌ Exception: " + e.getMessage());
        }

        // 🔥 Console visibility
        System.out.println("➡️ Result: " + result.result +
                " | Status: " + result.status +
                " | Time: " + result.timeMs + "ms");

        // 🔥 Slow links detection
        if (result.timeMs > 2000) {
            System.out.println("🐢 SLOW LINK: " + href + " | " + result.timeMs + "ms");
            Allure.step("🐢 Slow Link detected: " + result.timeMs + "ms");
        }

        report(result);

        assertResult(href, result);
    }

    // =========================
    // 🔥 VALIDATION LAYER
    // =========================
    private boolean isApi(String url) {
        return url != null &&
                (url.contains("/api") || url.contains("/v1") || url.contains("/v2"));
    }

    private LinkResult validateApi(String href) {
        Allure.step("Type: API");
        return ApiValidator.validate(href);
    }

    private LinkResult validateUi(String href) {
        Allure.step("Type: UI");
        return LinkValidator.validate(href, "");
    }

    // =========================
    // 🔥 REPORTING
    // =========================
    private void report(LinkResult result) {

        Allure.step("Status Code: " + result.status);
        Allure.step("Result: " + result.result);

        if ("ERROR".equals(result.result)) {
            Allure.step("⚠️ Network / Blocked / Timeout");
        }
    }

    // =========================
    // 🔥 ASSERTION
    // =========================
    private void assertResult(String href, LinkResult result) {

        if ("FAIL".equals(result.result)) {
            Assert.fail("❌ Broken Link: " + href);
        }
    }

    // =========================
    // 🔥 TEARDOWN
    // =========================
    @AfterClass(alwaysRun = true)
    public void tearDown() {

        System.out.println("🧹 Closing browser...");

        if (driver != null) {
            driver.quit();
        }
    }
}