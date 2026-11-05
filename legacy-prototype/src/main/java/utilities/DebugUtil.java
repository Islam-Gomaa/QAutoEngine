package utilities;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

public class DebugUtil {

    private static final boolean DEBUG = true;

    public static void slow(int ms) {
        if (!DEBUG) return;

        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }

    public static void highlight(WebDriver driver, WebElement element) {

        if (!DEBUG) return;

        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;

            js.executeScript(
                    "arguments[0].style.border='3px solid red'",
                    element
            );

            Thread.sleep(400);

        } catch (Exception ignored) {}
    }
}