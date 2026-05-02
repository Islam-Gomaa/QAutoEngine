package engine.intelligence;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.By;

import java.util.List;

public class ProgressEngine {

    // ===============================
    // 🔥 MAIN PROGRESS SCORE
    // ===============================
    public static double evaluateProgress(WebDriver driver,
                                          String beforeUrl,
                                          String afterUrl,
                                          int beforeDom,
                                          int afterDom) {

        double score = 0;

        // ================= URL CHANGE =================
        if (!safe(beforeUrl).equals(safe(afterUrl))) {
            score += 3;
        }

        // ================= URL DEPTH =================
        int beforeDepth = getDepth(beforeUrl);
        int afterDepth = getDepth(afterUrl);

        if (afterDepth > beforeDepth) {
            score += 2;
        }

        // ================= DOM CHANGE =================
        int delta = Math.abs(afterDom - beforeDom);

        if (delta > 50) score += 2;
        else if (delta > 10) score += 1;

        // ================= INTERACTION SIGNALS =================
        score += detectInteractiveElements(driver);

        // ================= URL SEMANTICS =================
        score += semanticUrlScore(afterUrl);

        return score;
    }

    // ===============================
    // 🧠 TERMINAL DETECTION
    // ===============================
    public static boolean isTerminal(WebDriver driver) {

        String page = driver.getPageSource().toLowerCase();

        // success indicators
        if (page.contains("success")
                || page.contains("completed")
                || page.contains("thank you")
                || page.contains("done")) {
            return true;
        }

        // no more actions
        List<WebElement> clickable = driver.findElements(By.xpath("//a | //button"));

        return clickable.isEmpty();
    }

    // ===============================
    // 🧠 STAGNATION DETECTION
    // ===============================
    public static boolean isStuck(int stepsWithoutProgress) {
        return stepsWithoutProgress >= 5;
    }

    // ===============================
    // 🔍 INTERACTION DETECTOR
    // ===============================
    private static double detectInteractiveElements(WebDriver driver) {

        double score = 0;

        List<WebElement> forms = driver.findElements(By.tagName("form"));
        List<WebElement> inputs = driver.findElements(By.xpath("//input | //textarea"));

        if (!forms.isEmpty()) score += 2;
        if (inputs.size() > 3) score += 1;

        return score;
    }

    // ===============================
    // 🔗 URL SEMANTIC INTELLIGENCE
    // ===============================
    private static double semanticUrlScore(String url) {

        if (url == null) return 0;

        url = url.toLowerCase();

        double score = 0;

        if (url.contains("detail")) score += 1.5;
        if (url.contains("view")) score += 1.2;
        if (url.contains("edit")) score += 1.5;
        if (url.contains("create")) score += 1.5;
        if (url.contains("form")) score += 2;
        if (url.contains("submit")) score += 2.5;
        if (url.contains("confirm")) score += 3;

        return score;
    }

    // ===============================
    // 🧠 URL DEPTH
    // ===============================
    private static int getDepth(String url) {

        if (url == null || url.isEmpty()) return 0;

        return url.split("/").length;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}