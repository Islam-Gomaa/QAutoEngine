package tests;

import engine.actions.*;
import engine.core.ExecutionEngine;
import engine.learning.*;
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

@Epic("AI Web Scanner")
@Feature("Crawler + AI + Validation + Training")
@Listeners({io.qameta.allure.testng.AllureTestNg.class})
public class CrawlerValidationTest {

    private long startTime;

    // ===================================================
    // 🧠 SUITE SETUP
    // ===================================================
    @BeforeSuite
    public void setupSuite() {

        AllureManager.cleanResults();

        // 🔥 Reset AI Memory (مرة واحدة بس)
        BehaviorGraph.reset();
        BehaviorStore.reset();

        System.out.println("🧠 AI Global Reset Done");
    }

    // ===================================================
    // 🧠 MAIN TEST
    // ===================================================
    @Test
    @Story("Full AI Scan + Training System")
    @Severity(SeverityLevel.CRITICAL)
    public void fullScanWithTraining() {

        startTime = System.currentTimeMillis();

        int runs = ConfigReader.getInt("ai.runs", 3);

        List<RunMetrics> metrics = new ArrayList<>();

        for (int i = 1; i <= runs; i++) {

            System.out.println("\n==============================");
            System.out.println("🧠 AI RUN #" + i);
            System.out.println("==============================");

            // 🔥 Reset session only
            SessionState.reset();
            LoopDetector.reset();

            WebDriver driver = DriverFactory.createDriver(
                    ConfigReader.get("browser")
            );

            driver.manage().timeouts().implicitlyWait(
                    Duration.ofSeconds(ConfigReader.getInt("timeout", 10))
            );

            String baseUrl = ConfigReader.get("url");
            driver.get(baseUrl);

            Set<String> links;

            long start = System.currentTimeMillis();

            try {

                // =========================
                // 🔥 PHASE 1: AI ENGINE
                // =========================
                Allure.step("🧠 Running AI Execution Engine");

                List<Action> actions = List.of(
                        new ClickAction(),
                        new FormAction(),
                        new NavigationAction()
                );

                new ExecutionEngine(actions).run(driver);

                // =========================
                // 🔥 PHASE 2: COLLECT LINKS
                // =========================
                Allure.step("🔗 Collecting Links After AI");

                links = new HashSet<>(LinkCollector.collect(driver));

                System.out.println("🔥 Links Collected: " + links.size());

            } finally {
                // سيبه مفتوح لو عايز تشوف الحركة
                // driver.quit();
            }

            // =========================
            // 🔥 PHASE 3: VALIDATION (Parallel)
            // =========================
            int threads = ConfigReader.getInt("threads", 5);
            ExecutorService executor = Executors.newFixedThreadPool(threads);

            List<LinkResult> results =
                    Collections.synchronizedList(new ArrayList<>());

            List<Future<?>> futures = new ArrayList<>();

            for (String link : links) {
                futures.add(executor.submit(() ->
                        validateAsTestCase(link, results)));
            }

            waitAll(futures);

            executor.shutdown();

            try {
                executor.awaitTermination(2, TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {}

            // =========================
            // 🔥 PHASE 4: METRICS
            // =========================
            long time = System.currentTimeMillis() - start;

            RunMetrics m = new RunMetrics();
            m.run = i;
            m.links = links.size();
            m.time = time;
            m.states = SessionState.getVisitedCount();

            metrics.add(m);

            System.out.println("📊 Run " + i +
                    " → Links: " + m.links +
                    " | States: " + m.states +
                    " | Time: " + m.time);

            // =========================
            // 🔥 SAVE LEARNING
            // =========================
            LearningPersistence.save(BehaviorGraph.getQTable());

            // =========================
            // 🔥 SUMMARY PER RUN
            // =========================
            generateSummary(results);
        }

        // =========================
        // 🔥 FINAL ANALYSIS
        // =========================
        analyzeLearning(metrics);
    }

    // ===================================================
    // 😈 كل لينك = Test Case مستقل في Allure
    // ===================================================
    private void validateAsTestCase(String link,
                                    List<LinkResult> results) {

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

            Allure.getLifecycle().updateTestCase(uuid,
                    t -> t.setStatus(Status.PASSED));

        } catch (Exception e) {

            Allure.step("❌ Failure: " + e.getMessage());

            Allure.getLifecycle().updateTestCase(uuid,
                    t -> t.setStatus(Status.FAILED));

        } finally {

            Allure.getLifecycle().stopTestCase(uuid);
            Allure.getLifecycle().writeTestCase(uuid);
        }
    }

    // ===================================================
    private boolean isApi(String url) {
        return url.contains("/api") ||
                url.contains("/v1") ||
                url.contains("/v2");
    }

    private void waitAll(List<Future<?>> futures) {

        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
    }

    // ===================================================
    private void generateSummary(List<LinkResult> results) {

        long pass = results.stream().filter(r -> "PASS".equals(r.result)).count();
        long fail = results.stream().filter(r -> "FAIL".equals(r.result)).count();

        System.out.println("\n===== RUN SUMMARY =====");
        System.out.println("Total: " + results.size());
        System.out.println("PASS: " + pass);
        System.out.println("FAIL: " + fail);

        if (fail > 0) {
            Assert.fail("❌ Broken links found: " + fail);
        }
    }

    // ===================================================
    // 🧠 LEARNING ANALYSIS
    // ===================================================
    private void analyzeLearning(List<RunMetrics> metrics) {

        System.out.println("\n🧠 AI LEARNING ANALYSIS");

        boolean improving = false;

        for (int i = 1; i < metrics.size(); i++) {

            RunMetrics prev = metrics.get(i - 1);
            RunMetrics curr = metrics.get(i);

            int linkDelta = curr.links - prev.links;
            int stateDelta = curr.states - prev.states;

            System.out.println("Run " + curr.run +
                    " vs Run " + prev.run +
                    " → ΔLinks=" + linkDelta +
                    " | ΔStates=" + stateDelta);

            if (linkDelta > 0 || stateDelta > 0) {
                improving = true;
            }
        }

        if (!improving) {
            Assert.fail("❌ AI is NOT learning");
        }
    }

    // ===================================================
    static class RunMetrics {
        int run;
        int links;
        int states;
        long time;
    }

    // ===================================================
    @AfterSuite
    public void afterSuite() {

        AllureManager.generateReport();

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("⏱ Total Execution Time: " + totalTime + " ms");
    }
}