package engine.decision;

import engine.learning.BehaviorGraph;
import engine.learning.BehaviorStore;
import engine.learning.LearningEngine.State;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.List;

public class DecisionEngine {

    // ===================================================
    // 🧠 MAIN ENTRY (🔥 الجديد)
    // ===================================================
    public static double scoreAction(String actionName,
                                     WebDriver driver,
                                     State state) {

        double score = 0;

        switch (actionName) {

            case "ClickAction":
                score += scoreBestClick(driver, state);
                break;

            case "FormAction":
                score += scoreBestForm(driver, state);
                break;

            case "NavigationAction":
                score += scoreNavigation(driver.getCurrentUrl(), state);
                break;

            default:
                score += 0;
        }

        return score;
    }

    // ===================================================
    // 🖱 CLICK (BEST ELEMENT 🔥)
    // ===================================================
    private static double scoreBestClick(WebDriver driver,
                                         State state) {

        List<WebElement> elements =
                driver.findElements(org.openqa.selenium.By.xpath("//button | //a | //input"));

        double best = -999;

        for (WebElement el : elements) {

            double s = scoreClick(el, state);

            if (s > best) best = s;
        }

        return best;
    }

    // ===================================================
    // 📝 FORM (BEST FIELD 🔥)
    // ===================================================
    private static double scoreBestForm(WebDriver driver,
                                        State state) {

        List<WebElement> elements =
                driver.findElements(org.openqa.selenium.By.xpath("//input | //textarea | //select"));

        double best = -999;

        for (WebElement el : elements) {

            double s = scoreForm(el, state);

            if (s > best) best = s;
        }

        return best;
    }

    // ===================================================
    // 🧠 CLICK SCORE (OLD + NEW)
    // ===================================================
    public static double scoreClick(WebElement el,
                                    State state) {

        double score = 0;

        try {

            String text = safe(el.getText()).toLowerCase();
            String tag = safe(el.getTagName()).toLowerCase();
            String href = el.getAttribute("href");
            String role = safe(el.getAttribute("role")).toLowerCase();

            String action = "ClickAction";

            // ❌ BLOCK
            if (text.contains("logout")) return -100;
            if (text.contains("delete")) return -100;
            if (text.contains("remove")) return -100;

            if (isExternal(href)) return -50;

            // 🎯 RULES
            if (text.contains("next")) score += 10;
            if (text.contains("submit")) score += 10;
            if (text.contains("continue")) score += 8;
            if (text.contains("checkout")) score += 12;

            if (tag.equals("button")) score += 8;
            if (tag.equals("a")) score += 5;
            if (tag.equals("input")) score += 6;

            if (role.contains("button")) score += 7;

            if (href != null && href.startsWith("http")) {
                score += 5;
            }

            if (!text.isEmpty()) {
                score += Math.min(text.length(), 5);
            }

            // 🧠 LEARNING
            score += BehaviorStore.getScore(state.toString(), action) * 10;

            // 🧠 GRAPH
            score += BehaviorGraph.getActionScore(state.toString(), action) * 5;

            // 🎲
            score += Math.random() * 0.5;

            return score;

        } catch (Exception e) {
            return -1;
        }
    }

    // ===================================================
    // 🌍 NAVIGATION
    // ===================================================
    public static double scoreNavigation(String url,
                                         State state) {

        if (url == null) return -100;

        url = url.toLowerCase();

        double score = 0;

        String action = "NavigationAction";

        if (url.contains("logout")) return -100;
        if (url.contains("delete")) return -100;

        if (isExternal(url)) return -50;

        if (url.contains("product")) score += 10;
        if (url.contains("cart")) score += 12;
        if (url.contains("checkout")) score += 15;
        if (url.contains("next")) score += 6;

        score += 3;

        score += BehaviorStore.getScore(state.toString(), action) * 10;
        score += BehaviorGraph.getActionScore(state.toString(), action) * 5;

        score += Math.random() * 0.5;

        return score;
    }

    // ===================================================
    // 📝 FORM
    // ===================================================
    public static double scoreForm(WebElement el,
                                   State state) {

        double score = 0;

        try {

            String type = safe(el.getAttribute("type")).toLowerCase();
            String name = safe(el.getAttribute("name")).toLowerCase();

            String action = "FormAction";

            if (type.contains("hidden")) return -100;
            if (type.contains("file")) return -50;

            if (type.contains("email")) score += 10;
            if (type.contains("password")) score += 12;
            if (type.contains("text")) score += 6;

            if (name.contains("email")) score += 10;
            if (name.contains("name")) score += 5;

            score += 3;

            score += BehaviorStore.getScore(state.toString(), action) * 10;
            score += BehaviorGraph.getActionScore(state.toString(), action) * 5;

            score += Math.random() * 0.5;

            return score;

        } catch (Exception e) {
            return -1;
        }
    }

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

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}