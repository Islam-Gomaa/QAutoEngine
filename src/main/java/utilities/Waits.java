package utilities;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Wait;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

public class Waits {

    private static final int DEFAULT_TIMEOUT = 2;
    private static final int POLLING = 500;

    // ================= WAIT BUILDER =================

    private static Wait<WebDriver> getWait(WebDriver driver) {
        return getWait(driver, DEFAULT_TIMEOUT);
    }

    private static Wait<WebDriver> getWait(WebDriver driver, int timeout) {
        return new WebDriverWait(driver, Duration.ofSeconds(timeout))
                .pollingEvery(Duration.ofMillis(POLLING))
                .ignoring(NoSuchElementException.class)
                .ignoring(StaleElementReferenceException.class);
    }

    // ================= PAGE LOAD / AJAX =================

    public static void waitForPageLoad(WebDriver driver) {
        getWait(driver).until(d ->
                ((JavascriptExecutor) d)
                        .executeScript("return document.readyState")
                        .equals("complete")
        );
    }

    public static void waitForAjax(WebDriver driver) {
        getWait(driver).until(d ->
                "complete".equals(
                        ((JavascriptExecutor) d)
                                .executeScript("return document.readyState")
                )
        );
    }

    // ================= VISIBILITY =================

    public static WebElement waitForVisible(WebDriver driver, By locator) {
        return getWait(driver).until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    public static WebElement waitForVisible(WebDriver driver, By locator, int timeout) {
        return getWait(driver, timeout).until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    public static List<WebElement> waitForAllVisible(WebDriver driver, By locator) {
        return getWait(driver).until(ExpectedConditions.visibilityOfAllElementsLocatedBy(locator));
    }

    public static List<WebElement> waitForAllVisible(WebDriver driver, By locator, int timeout) {
        return getWait(driver, timeout).until(ExpectedConditions.visibilityOfAllElementsLocatedBy(locator));
    }

    // ================= CLICKABLE =================

    public static WebElement waitForClickable(WebDriver driver, By locator) {
        return getWait(driver).until(ExpectedConditions.elementToBeClickable(locator));
    }

    public static WebElement waitForClickable(WebDriver driver, By locator, int timeout) {
        return getWait(driver, timeout).until(ExpectedConditions.elementToBeClickable(locator));
    }

    public static WebElement waitForClickable(WebDriver driver, WebElement element) {
        return getWait(driver).until(ExpectedConditions.elementToBeClickable(element));
    }

    // ================= PRESENCE =================

    public static WebElement waitForPresence(WebDriver driver, By locator) {
        return getWait(driver).until(ExpectedConditions.presenceOfElementLocated(locator));
    }

    public static WebElement waitForPresence(WebDriver driver, By locator, int timeout) {
        return getWait(driver, timeout).until(ExpectedConditions.presenceOfElementLocated(locator));
    }

    // ================= INVISIBILITY =================

    public static boolean waitForInvisibility(WebDriver driver, By locator) {
        return getWait(driver).until(ExpectedConditions.invisibilityOfElementLocated(locator));
    }

    public static boolean waitForInvisibility(WebDriver driver, By locator, int timeout) {
        return getWait(driver, timeout).until(ExpectedConditions.invisibilityOfElementLocated(locator));
    }

    // ================= TEXT =================

    public static String waitForText(WebDriver driver, By locator) {
        return getWait(driver).until(d -> {
            try {
                String text = d.findElement(locator).getText();
                return text != null && !text.trim().isEmpty() ? text : null;
            } catch (StaleElementReferenceException e) {
                return null;
            }
        });
    }

    public static boolean waitForTextToBe(WebDriver driver, By locator, String expectedText) {
        return getWait(driver).until(d -> {
            try {
                return expectedText.equals(d.findElement(locator).getText());
            } catch (StaleElementReferenceException e) {
                return false;
            }
        });
    }

    public static boolean waitForTableResultOrNoData(WebDriver driver, By dataLocator, By noDataLocator) {
        return getWait(driver).until(d -> {
            try {
                return !d.findElements(dataLocator).isEmpty()
                        || !d.findElements(noDataLocator).isEmpty();
            } catch (Exception e) {
                return false;
            }
        });
    }

    // ================= ATTRIBUTE =================

    public static boolean waitForAttributeContains(WebDriver driver, By locator, String attribute, String value) {
        return getWait(driver).until(ExpectedConditions.attributeContains(locator, attribute, value));
    }

    // ================= ALERT =================

    public static Alert waitForAlert(WebDriver driver) {
        return getWait(driver).until(ExpectedConditions.alertIsPresent());
    }

    // ================= FRAME =================

    public static void waitForFrameAndSwitch(WebDriver driver, By locator) {
        getWait(driver).until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(locator));
    }

    // =============== ELEMENT WAITS =================

    public static void waitForResultsToAppear(WebDriver driver, By locator, int number) {
        getWait(driver).until(ExpectedConditions.numberOfElementsToBeMoreThan(locator, number));
    }
}