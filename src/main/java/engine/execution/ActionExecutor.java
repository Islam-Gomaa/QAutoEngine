package engine.execution;

import ai.learning.State;
import ai.learning.LoopDetector;

import org.openqa.selenium.*;
import utilities.Waits;

public class ActionExecutor {

    private static final int MAX_RETRIES = 3;

    // ===================================================
    // 🧠 RESULT MODEL
    // ===================================================
    public static class Result {
        public boolean success;
        public long duration;
        public String error;

        public Result(boolean success, long duration, String error) {
            this.success = success;
            this.duration = duration;
            this.error = error;
        }
    }

    // ===================================================
    // 🔥 EXECUTE (V6)
    // ===================================================
    public static Result execute(WebDriver driver,
                                 Runnable action,
                                 String name,
                                 State state) {

        int retries = 0;
        long start = System.currentTimeMillis();

        while (retries <= MAX_RETRIES) {

            try {

                action.run();

                Waits.waitForPageLoad(driver);
                handleAlert(driver);

                long duration = System.currentTimeMillis() - start;

                System.out.println("✅ " + name + " | " + duration + "ms");

                return new Result(true, duration, null);

            } catch (UnhandledAlertException e) {

                handleAlert(driver);

            } catch (StaleElementReferenceException e) {

                System.out.println("🔄 Stale retry: " + name);
                Waits.waitForPageLoad(driver);

            } catch (TimeoutException e) {

                System.out.println("⏳ Timeout in: " + name);
                recover(driver);

            } catch (WebDriverException e) {

                System.out.println("⚠️ WebDriver issue: " + name);
                recover(driver);

            } catch (Exception e) {

                System.out.println("❌ Error in: " + name + " → " + e.getMessage());

                if (retries == MAX_RETRIES) {
                    return new Result(false,
                            System.currentTimeMillis() - start,
                            e.getMessage());
                }
            }

            retries++;
        }

        return new Result(false,
                System.currentTimeMillis() - start,
                "Max retries exceeded");
    }

    // ===================================================
    // ⚠️ ALERT HANDLING
    // ===================================================
    private static void handleAlert(WebDriver driver) {

        try {
            Alert alert = driver.switchTo().alert();
            System.out.println("⚠️ Alert: " + alert.getText());
            alert.accept();
        } catch (NoAlertPresentException ignored) {}
    }

    // ===================================================
    // 🔥 SMART RECOVERY
    // ===================================================
    private static void recover(WebDriver driver) {

        try {

            // محاولة back
            driver.navigate().back();
            Waits.waitForPageLoad(driver);

        } catch (Exception ignored) {

            try {
                // fallback → refresh
                driver.navigate().refresh();
                Waits.waitForPageLoad(driver);
            } catch (Exception ignored2) {}
        }
    }
}