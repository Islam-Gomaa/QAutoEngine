package engine.core;

import engine.distribution.DistributionManager;
import utilities.ConfigReader;
import utilities.DriverFactory;
import utilities.DriverManager;

import java.time.Duration;
import java.util.concurrent.*;

public class ParallelEngine {

    private final int threads;
    private final int maxRetries;

    public ParallelEngine() {
        this.threads = Integer.parseInt(ConfigReader.get("threads"));
        this.maxRetries = Integer.parseInt(ConfigReader.get("retries", "2"));
    }

    public void run() {

        System.out.println("🚀 Parallel Engine START | Threads: " + threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        CompletionService<Void> completionService =
                new ExecutorCompletionService<>(executor);

        for (int i = 0; i < threads; i++) {

            int workerId = i + 1;

            completionService.submit(() -> {
                runWorker(workerId);
                return null;
            });
        }

        // 🔥 wait for all workers
        for (int i = 0; i < threads; i++) {
            try {
                completionService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        executor.shutdown();

        System.out.println("✅ Parallel Engine FINISHED");
    }

    // ===================================================
    // 🔥 WORKER CORE
    // ===================================================
    private void runWorker(int id) {

        System.out.println("🧵 Worker " + id + " START");

        int retry = 0;

        while (retry <= maxRetries) {

            try {

                var driver = DriverFactory.createDriver(ConfigReader.get("browser"));
                DriverManager.setDriver(driver);

                driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));

                processQueue(driver, id);

                break; // ✅ success → break retry loop

            } catch (Exception e) {

                retry++;

                System.out.println("❌ Worker " + id + " crashed | retry: " + retry);

                if (retry > maxRetries) {
                    System.out.println("💀 Worker " + id + " FAILED permanently");
                }

            } finally {

                DriverManager.quitDriver();
            }
        }

        System.out.println("🧵 Worker " + id + " END");
    }

    // ===================================================
    // 🔥 PROCESS QUEUE (AI Distribution)
    // ===================================================
    private void processQueue(org.openqa.selenium.WebDriver driver, int id) {

        EngineOrchestrator orchestrator = new EngineOrchestrator();

        while (true) {

            String url = DistributionManager.next();

            if (url == null) break;

            try {

                System.out.println("🌍 Worker " + id + " visiting: " + url);

                driver.get(url);

                orchestrator.run(driver);

            } catch (Exception e) {

                System.out.println("⚠️ Failed URL: " + url);
            }
        }
    }
}