package engine.decision;

import engine.learning.BehaviorStore;
import org.openqa.selenium.WebElement;

public class ScoreCalculator {

    public static double scoreElement(WebElement el) {

        double score = 0;

        try {

            String text = safe(el.getText());
            String type = safe(el.getAttribute("type"));
            String tag = safe(el.getTagName());
            String id = safe(el.getAttribute("id"));
            String clazz = safe(el.getAttribute("class"));

            String combined = (text + type + tag + id + clazz).toLowerCase();

            // ===================================================
            // 🔥 1. POSITIVE SIGNALS
            // ===================================================
            if (combined.contains("next")) score += 80;
            if (combined.contains("submit")) score += 70;
            if (combined.contains("continue")) score += 60;
            if (combined.contains("ok")) score += 50;
            if (combined.contains("search")) score += 40;

            // ===================================================
            // 🚫 2. NEGATIVE / DANGEROUS
            // ===================================================
            if (combined.contains("cancel")) score -= 60;
            if (combined.contains("close")) score -= 70;
            if (combined.contains("logout")) score -= 200;
            if (combined.contains("delete")) score -= 300;
            if (combined.contains("remove")) score -= 150;

            // ===================================================
            // 🧠 3. LEARNING BOOST
            // ===================================================
            String key = buildKey(el);

            score += BehaviorStore.getScore(key) * 100;

            // ===================================================
            // 🔁 4. FAILURE PENALTY
            // ===================================================
            score -= BehaviorStore.getFailure(key) * 30;

            // ===================================================
            // ⚖️ 5. EXPLORATION (AI behavior)
            // ===================================================
            score += Math.random() * 5;

            // ===================================================
            // 🧱 6. VISIBILITY BOOST
            // ===================================================
            if (el.isDisplayed()) score += 10;
            if (el.isEnabled()) score += 10;

        } catch (Exception ignored) {}

        return score;
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }

    private static String buildKey(WebElement el) {
        try {
            return el.getTagName() + "|" + el.getText();
        } catch (Exception e) {
            return "unknown";
        }
    }
}