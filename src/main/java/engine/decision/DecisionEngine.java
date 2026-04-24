package engine.decision;

import engine.learning.LearningEngine;
import org.openqa.selenium.WebElement;

public class DecisionEngine {

    // ===================================================
    // 🖱️ CLICK DECISION
    // ===================================================
    public static boolean shouldClick(WebElement el) {

        try {

            String text = el.getText().toLowerCase();
            String tag = el.getTagName().toLowerCase();
            String href = el.getAttribute("href");

            // 🚫 skip empty elements
            if (text.isEmpty() && href == null) return false;

            // 🚫 skip social / external
            if (isExternal(href)) return false;

            // 🚫 skip dangerous actions
            if (text.contains("logout")) return false;
            if (text.contains("delete")) return false;

            // ✅ allow useful actions
            if (text.contains("next")) return true;
            if (text.contains("submit")) return true;
            if (text.contains("ok")) return true;
            if (text.contains("yes")) return true;

            // ⚖️ fallback scoring
            String key = el.getText() + el.getTagName();

            double score = ScoreCalculator.scoreElement(el);

            return score >= 30;

        } catch (Exception e) {
            return false;
        }
    }

    // ===================================================
    // 🌍 NAVIGATION DECISION
    // ===================================================
    public static boolean shouldNavigate(String url) {

        if (url == null) return false;

        url = url.toLowerCase();

        // 🚫 block external
        if (isExternal(url)) return false;

        // 🚫 block logout/login loops
        if (url.contains("logout")) return false;

        // 🚫 block social
        if (url.contains("facebook") ||
                url.contains("instagram") ||
                url.contains("whatsapp") ||
                url.contains("linkedin") ||
                url.contains("twitter")) return false;

        return true;
    }

    // ===================================================
    // 📝 FORM DECISION
    // ===================================================
    public static boolean shouldFill(WebElement el) {

        try {

            String type = el.getAttribute("type");
            String name = el.getAttribute("name");

            if (type == null) return false;

            type = type.toLowerCase();

            // 🚫 skip hidden / file
            if (type.contains("hidden")) return false;
            if (type.contains("file")) return false;

            // 🚫 skip password sometimes (optional)
            if (type.contains("password")) return true;

            return true;

        } catch (Exception e) {
            return false;
        }
    }

    // ===================================================
    // 🌍 HELPER
    // ===================================================
    private static boolean isExternal(String url) {

        if (url == null) return false;

        url = url.toLowerCase();

        return url.contains("facebook") ||
                url.contains("instagram") ||
                url.contains("whatsapp") ||
                url.contains("linkedin") ||
                url.contains("twitter");
    }
}