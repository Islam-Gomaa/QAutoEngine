package ai.learning;

import ai.goal.GoalEngine;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.List;

public class StateFactory {

    private StateFactory() {}

    // ===================================================
    // 🧠 BUILD STATE (V6.1 SMART)
    // ===================================================
    public static State build(
            WebDriver driver
    ) {

        if (driver == null) {

            return emptyState();
        }

        try {

            // ===========================================
            // URL
            // ===========================================
            String url =
                    normalizeUrl(
                            driver.getCurrentUrl()
                    );

            // ===========================================
            // DOM
            // ===========================================
            int domSize =
                    driver.findElements(
                            By.xpath("//*")
                    ).size();

            // ===========================================
            // FORMS
            // ===========================================
            List<WebElement> forms =
                    driver.findElements(
                            By.tagName("form")
                    );

            boolean hasForm =
                    !forms.isEmpty();

            // ===========================================
            // LINKS
            // ===========================================
            List<WebElement> links =
                    driver.findElements(
                            By.tagName("a")
                    );

            boolean hasLinks =
                    !links.isEmpty();

            // ===========================================
            // INPUT COUNT
            // ===========================================
            int inputs =
                    driver.findElements(
                            By.xpath(
                                    "//input | //textarea | //select"
                            )
                    ).size();

            // ===========================================
            // CLICKABLE COUNT
            // ===========================================
            int clickable =
                    driver.findElements(
                            By.xpath(
                                    "//button | //*[@role='button']"
                            )
                    ).size();

            // ===========================================
            // PAGE TITLE
            // ===========================================
            String title =
                    safe(
                            driver.getTitle()
                    );

            int titleHash =
                    Math.abs(
                            title.hashCode()
                    ) % 1000;

            // ===========================================
            // GOAL
            // ===========================================
            String goal =
                    GoalEngine
                            .getCurrentGoal()
                            != null
                            ? GoalEngine
                            .getCurrentGoal()
                            .name
                            : "UNKNOWN";

            // ===========================================
            // SMART DOM BUCKET
            // ===========================================
            int smartDom =
                    domSize
                            + (inputs * 10)
                            + (clickable * 5)
                            + titleHash;

            return new State(
                    url,
                    smartDom,
                    goal,
                    hasForm,
                    hasLinks
            );

        } catch (Exception e) {

            System.out.println(
                    "⚠️ StateFactory failed → "
                            + e.getMessage()
            );

            return emptyState();
        }
    }

    // ===================================================
    // 🔥 URL NORMALIZATION
    // ===================================================
    private static String normalizeUrl(
            String url
    ) {

        if (url == null) {
            return "NULL";
        }

        try {

            // remove query params
            int q =
                    url.indexOf("?");

            if (q > 0) {
                url =
                        url.substring(0, q);
            }

            // remove trailing slash
            if (url.endsWith("/")) {
                url =
                        url.substring(
                                0,
                                url.length() - 1
                        );
            }

            return url
                    .toLowerCase();

        } catch (Exception e) {

            return url;
        }
    }

    // ===================================================
    // 🔧 SAFE STRING
    // ===================================================
    private static String safe(
            String value
    ) {

        return value == null
                ? ""
                : value.trim();
    }

    // ===================================================
    // 🧠 EMPTY STATE
    // ===================================================
    private static State emptyState() {

        return new State(
                "NULL",
                0,
                "UNKNOWN",
                false,
                false
        );
    }
}