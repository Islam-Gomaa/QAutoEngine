package tests;

import engine.core.ScannerEngine;
import engine.state.SessionState;
import io.qameta.allure.*;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.TestResult;
import models.LinkResult;
import org.openqa.selenium.WebDriver;
import org.testng.Assert;
import org.testng.annotations.*;
import utilities.AllureManager;
import utilities.ConfigReader;
import utilities.DriverFactory;
import crawler.LinkCollector;
import validators.ApiValidator;
import validators.LinkValidator;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Epic("Web Scanner")
@Feature("Crawler + Validation")
@Listeners({io.qameta.allure.testng.AllureTestNg.class})
public class CrawlerValidationTest {

    private long startTime;

    @BeforeSuite
    public void setupSuite() {
        AllureManager.cleanResults();
    }

    @Test
    @Story("Full Scan System")
    @Severity(SeverityLevel.CRITICAL)
    public void fullScan() {

        startTime = System.currentTimeMillis();

        WebDriver driver = DriverFactory.createDriver(
                ConfigReader.get("browser")
        );

        driver.manage().timeouts().implicitlyWait(
                Duration.ofSeconds(ConfigReader.getInt("timeout", 10))
        );

        String baseUrl = ConfigReader.get("url");
        driver.get(baseUrl);

        SessionState.reset();

        Set<String> links;

        try {
            ScannerEngine scanner = new ScannerEngine();
            links = scanner.runAndCollect(driver);

            if (links == null || links.isEmpty()) {
                links = new HashSet<>(LinkCollector.collect(driver));
            }

            System.out.println("🔗 Total Links: " + links.size());

        } finally {
            driver.quit();
        }

        // =========================
        // 🔥 VALIDATION (Parallel)
        // =========================
        int threads = ConfigReader.getInt("threads", 5);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        List<LinkResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        for (String link : links) {
            futures.add(executor.submit(() -> validateAsTestCase(link, results)));
        }

        waitAll(futures);

        executor.shutdown();

        try {
            executor.awaitTermination(2, TimeUnit.MINUTES);
        } catch (InterruptedException ignored) {}

        generateSummary(results);
    }

    @AfterSuite
    public void afterSuite() {

        AllureManager.generateReport();

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("⏱ Total Execution Time: " + totalTime + " ms");
    }

    // ===================================================
    // 😈 كل لينك = Test Case مستقل
    // ===================================================
    private void validateAsTestCase(String link, List<LinkResult> results) {

        String uuid = UUID.randomUUID().toString();

        TestResult testResult = new TestResult()
                .setUuid(uuid)
                .setName("🔗 " + link)
                .setFullName("Link Validation");

        Allure.getLifecycle().scheduleTestCase(testResult);
        Allure.getLifecycle().startTestCase(uuid);

        try {

            LinkResult result;

            if (isApi(link)) {
                result = ApiValidator.validate(link);
            } else {
                result = LinkValidator.validate(link, "");
            }

            results.add(result);

            Allure.step("Status: " + result.status);
            Allure.step("Result: " + result.result);

            if (!"PASS".equals(result.result)) {
                throw new RuntimeException("Validation Failed");
            }

            Allure.getLifecycle().updateTestCase(uuid, t -> t.setStatus(Status.PASSED));

        } catch (Exception e) {

            Allure.step("❌ Failure: " + e.getMessage());

            Allure.getLifecycle().updateTestCase(uuid, t -> t.setStatus(Status.FAILED));

        } finally {

            Allure.getLifecycle().stopTestCase(uuid);
            Allure.getLifecycle().writeTestCase(uuid);
        }
    }

    // ===================================================
    private boolean isApi(String url) {
        return url.contains("/api") || url.contains("/v1") || url.contains("/v2");
    }

    private void waitAll(List<Future<?>> futures) {

        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                System.out.println("⚠️ Task timeout");
            } catch (Exception ignored) {}
        }
    }

    // ===================================================
    // 📊 FINAL SUMMARY
    // ===================================================
    private void generateSummary(List<LinkResult> results) {

        long pass = results.stream().filter(r -> "PASS".equals(r.result)).count();
        long fail = results.stream().filter(r -> "FAIL".equals(r.result)).count();
        long slow = results.stream().filter(r -> "SLOW".equals(r.result)).count();
        long error = results.stream().filter(r -> "ERROR".equals(r.result)).count();

        System.out.println("\n===== FINAL SUMMARY =====");
        System.out.println("Total: " + results.size());
        System.out.println("PASS: " + pass);
        System.out.println("FAIL: " + fail);
        System.out.println("SLOW: " + slow);
        System.out.println("ERROR: " + error);

        if (fail > 0) {
            Assert.fail("❌ Broken links found: " + fail);
        }
    }
}