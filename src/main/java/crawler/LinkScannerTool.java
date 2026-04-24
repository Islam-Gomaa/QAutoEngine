package crawler;

import engine.core.ScannerEngine;
import io.qameta.allure.Allure;
import models.LinkResult;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utilities.*;
import validators.LinkValidator;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class LinkScannerTool {

    private static final Logger log = LoggerFactory.getLogger(LinkScannerTool.class);

    public void scan() {

        // ================= CONFIG =================
        String url = ConfigReader.get("url");
        String browser = ConfigReader.get("browser");

        int threads = ConfigReader.getInt("threads", 5);
        int timeout = ConfigReader.getInt("timeout", 10);
        int maxParallel = ConfigReader.getInt("maxParallel", 5);

        log.info("🚀 Starting scan on: {}", url);

        WebDriver driver = DriverFactory.createDriver(browser);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(timeout));
        driver.get(url);

        Set<String> links;

        try {
            // ================= SCAN =================
            ScannerEngine scanner = new ScannerEngine();
            links = scanner.runAndCollect(driver);

        } finally {
            driver.quit();
        }

        log.info("🔎 Total links collected: {}", links.size());

        // ================= VALIDATE =================
        List<LinkResult> results = validateLinks(links, threads, maxParallel);

        // ================= REPORT =================
        generateReport(results);
        ExcelExporter.export(results);

        log.info("📁 Report generated successfully");
    }

    // =========================================================
    // 🔥 VALIDATION ENGINE
    // =========================================================
    private List<LinkResult> validateLinks(Set<String> links,
                                           int threads,
                                           int maxParallel) {

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        Semaphore limiter = new Semaphore(maxParallel);

        List<LinkResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        for (String link : links) {

            futures.add(executor.submit(() -> {

                processLink(link, results, limiter);

            }));
        }

        waitAll(futures);
        executor.shutdown();

        return results;
    }

    // =========================================================
    private void processLink(String url,
                             List<LinkResult> results,
                             Semaphore limiter) {

        Allure.step("🔗 Checking: " + url);

        if (!isValid(url)) {
            results.add(new LinkResult(url, "", 0, 0, "SKIP"));
            return;
        }

        if (isAuth(url)) {
            results.add(new LinkResult(url, "", 0, 0, "SKIPPED_AUTH"));
            return;
        }

        try {
            limiter.acquire();

            LinkResult result = LinkValidator.validate(url, "");
            results.add(result);

            Allure.step("Status: " + result.status);
            Allure.step("Result: " + result.result);

            if (!"PASS".equals(result.result)) {
                captureFailure(result);
            }

            log.info("{} -> {}", result.result, url);

        } catch (Exception e) {

            results.add(new LinkResult(url, "", -1, 0, "ERROR"));
            log.error("❌ Error: {}", url, e);

        } finally {
            limiter.release();
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private void waitAll(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
    }

    private boolean isValid(String url) {
        return url != null && url.startsWith("http");
    }

    private boolean isAuth(String url) {
        return url.contains("login") ||
                url.contains("auth") ||
                url.contains("signup");
    }

    private void captureFailure(LinkResult result) {

        WebDriver temp = DriverFactory.createDriver("headlesschrome");

        try {
            temp.get(result.url);
            ScreenshotUtil.captureAndAttach(temp, "Broken - " + result.url);
        } finally {
            temp.quit();
        }
    }

    private void generateReport(List<LinkResult> results) {

        long pass = results.stream().filter(r -> "PASS".equals(r.result)).count();
        long fail = results.stream().filter(r -> "FAIL".equals(r.result)).count();
        long slow = results.stream().filter(r -> "SLOW".equals(r.result)).count();

        log.info("\n===== FINAL REPORT =====");
        log.info("Total: {}", results.size());
        log.info("PASS: {}", pass);
        log.info("FAIL: {}", fail);
        log.info("SLOW: {}", slow);
    }
}