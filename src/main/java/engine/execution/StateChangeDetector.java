package engine.execution;

import ai.learning.State;

import org.openqa.selenium.*;

public class StateChangeDetector {

    // ===================================================
    // 🧠 SNAPSHOT
    // ===================================================
    public static class StateSnapshot {

        public String url;
        public int domSize;
        public String title;

        public boolean hasForm;
        public boolean hasLinks;

        public StateSnapshot(WebDriver driver) {

            this.url = driver.getCurrentUrl();
            this.domSize = driver.findElements(By.xpath("//*")).size();
            this.title = safe(driver.getTitle());

            this.hasForm = !driver.findElements(By.tagName("form")).isEmpty();
            this.hasLinks = !driver.findElements(By.tagName("a")).isEmpty();
        }
    }

    // ===================================================
    // 🔥 MAIN CHANGE SCORE (V6)
    // ===================================================
    public static double evaluateChange(WebDriver driver,
                                        StateSnapshot before) {

        try {

            StateSnapshot after = new StateSnapshot(driver);

            double score = 0;

            // ================= URL =================
            if (!safe(before.url).equals(safe(after.url))) {
                score += 3;
            }

            // ================= TITLE =================
            if (!safe(before.title).equals(safe(after.title))) {
                score += 1.5;
            }

            // ================= DOM =================
            int delta = Math.abs(after.domSize - before.domSize);

            if (delta > 80) score += 2;
            else if (delta > 20) score += 1;

            // ================= CONTEXT =================
            if (before.hasForm != after.hasForm) score += 1.5;
            if (before.hasLinks != after.hasLinks) score += 1;

            // ================= SEMANTIC URL =================
            score += semanticScore(after.url);

            return clamp(score, 0, 10);

        } catch (Exception e) {
            return 0;
        }
    }

    // ===================================================
    // 🔥 QUICK BOOLEAN (for compatibility)
    // ===================================================
    public static boolean hasMeaningfulChange(WebDriver driver,
                                              StateSnapshot before) {

        return evaluateChange(driver, before) > 2;
    }

    // ===================================================
    // 🔥 REGRESSION DETECTION
    // ===================================================
    public static boolean isRegression(StateSnapshot before,
                                       StateSnapshot after) {

        if (before == null || after == null) return false;

        // رجعنا ل URL أبسط
        if (depth(after.url) < depth(before.url)) {
            return true;
        }

        return false;
    }

    // ===================================================
    private static double semanticScore(String url) {

        if (url == null) return 0;

        url = url.toLowerCase();

        double score = 0;

        if (url.contains("detail")) score += 1;
        if (url.contains("form")) score += 1.5;
        if (url.contains("submit")) score += 2;
        if (url.contains("confirm")) score += 2.5;

        return score;
    }

    private static int depth(String url) {
        if (url == null) return 0;
        return url.split("/").length;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}