package crawler;

import org.openqa.selenium.*;
import utilities.Waits;

import java.util.*;

public class LinkCollector {

    public static List<String> collect(WebDriver driver) {

        Set<String> links = new HashSet<>();

        try {
            // 🔥 ensure DOM stable
            Waits.waitForPageLoad(driver);

            List<WebElement> elements = driver.findElements(By.tagName("a"));

            for (WebElement el : elements) {

                try {
                    String href = el.getAttribute("href");

                    if (href == null) continue;
                    if (!href.startsWith("http")) continue;

                    links.add(normalize(href));

                } catch (StaleElementReferenceException ignored) {}
            }

        } catch (Exception e) {
            System.out.println("⚠️ LinkCollector error: " + e.getMessage());
        }

        return new ArrayList<>(links);
    }

    // =========================
    // 🔥 NORMALIZATION (important)
    // =========================
    private static String normalize(String url) {

        if (url == null) return "";

        // remove #section
        url = url.split("#")[0];

        // remove trailing slash
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        return url.trim();
    }
}