package engine.core;

import org.openqa.selenium.*;
import utilities.Waits;

public class ActionExecutor {

    private static final int MAX_RETRIES = 2;

    public static void execute(WebDriver driver, Runnable action, String name) {

        int retries = 0;

        while (retries <= MAX_RETRIES) {
            try {

                action.run();

                handleAlert(driver);

                return;

            } catch (UnhandledAlertException e) {

                handleAlert(driver);

            } catch (StaleElementReferenceException e) {

                Waits.waitForPageLoad(driver);

            } catch (WebDriverException e) {

                System.out.println("⚠️ WebDriver issue in: " + name);

                recover(driver);

            } catch (Exception e) {

                System.out.println("❌ Error in: " + name);

                if (retries == MAX_RETRIES) return;
            }

            retries++;
        }
    }

    // ===================================================
    private static void handleAlert(WebDriver driver) {
        try {
            Alert alert = driver.switchTo().alert();
            System.out.println("⚠️ Alert: " + alert.getText());
            alert.accept();
        } catch (NoAlertPresentException ignored) {}
    }

    // ===================================================
    private static void recover(WebDriver driver) {

        try {
            driver.navigate().back();
            Waits.waitForPageLoad(driver);
        } catch (Exception ignored) {}
    }
}