package engine.decision;

import engine.intelligence.ProgressEngine;
import engine.learning.BehaviorGraph;
import engine.learning.BehaviorStore;
import engine.learning.LearningEngine.State;
import engine.state.SessionState;

import org.openqa.selenium.*;

import java.util.*;

public class DecisionEngine {

    // ===================================================
    // 🧠 MAIN ENTRY (UNIVERSAL)
    // ===================================================
    public static double scoreAction(String actionName,
                                     WebDriver driver,
                                     State state) {

        double score = 0;

        // ===============================================
        // 🔥 BASE SCORING
        // ===============================================
        switch (actionName) {

            case "ClickAction":
                score += scoreBestClick(driver, state);
                break;

            case "FormAction":
                score += scoreBestForm(driver, state);
                break;

            case "NavigationAction":
                score += scoreBestNavigation(driver, state);
                break;
        }

        // ===============================================
        // 🧠 MEMORY (BehaviorStore)
        // ===============================================
        double memory = BehaviorStore.getScore(state.toString(), actionName);
        score += memory * 12;

        // ===============================================
        // 🧠 GRAPH LEARNING
        // ===============================================
        double graph = BehaviorGraph.getActionScore(state.toString(), actionName);
        score += graph * 6;

        // ===============================================
        // 💣 REWARD MEMORY (SessionState RL)
        // ===============================================
        double reward = SessionState.getReward(state.toString());
        score += reward * 2;

        // ===============================================
        // 💣 LOOP DETECTION
        // ===============================================
        if (SessionState.isLooping()) {
            score -= 25;
        }

        // ===============================================
        // 💣 STUCK DETECTION
        // ===============================================
        if (SessionState.isStuck()) {

            // Escape logic
            if (actionName.equals("NavigationAction")) score += 15;
            if (actionName.equals("ClickAction")) score += 5;
        }

        // ===============================================
        // 💣 VISIT FREQUENCY
        // ===============================================
        int visits = SessionState.getUrlVisitCount(state.pageType);

        if (visits > 2) score -= 12;

        // ===============================================
        // 💣 SCENARIO AWARENESS
        // ===============================================
        score += scenarioBoost(state, actionName);

        // ===============================================
        // 💣 PATH AWARENESS
        // ===============================================
        score += pathAwareness();

        // ===============================================
        // 🎲 EXPLORATION
        // ===============================================
        score += Math.random();

        return score;
    }

    // ===================================================
    // 🖱 CLICK SCORING
    // ===================================================
    private static double scoreBestClick(WebDriver driver,
                                         State state) {

        List<WebElement> elements =
                driver.findElements(By.xpath("//button | //a | //*[@role='button']"));

        double best = -999;

        for (WebElement el : elements) {

            double s = scoreClick(el, driver, state);

            if (s > best) best = s;
        }

        return best;
    }

    private static double scoreClick(WebElement el,
                                     WebDriver driver,
                                     State state) {

        try {

            String text = safe(el.getText()).toLowerCase();
            String tag = safe(el.getTagName()).toLowerCase();

            if (text.contains("logout") || text.contains("delete"))
                return -100;

            double score = 0;

            if (text.contains("next")) score += 12;
            if (text.contains("submit")) score += 14;
            if (text.contains("continue")) score += 10;
            if (tag.equals("button")) score += 8;

            score += predictProgress(driver, state);

            return score;

        } catch (Exception e) {
            return -1;
        }
    }

    // ===================================================
    // 📝 FORM SCORING
    // ===================================================
    private static double scoreBestForm(WebDriver driver,
                                        State state) {

        List<WebElement> forms =
                driver.findElements(By.tagName("form"));

        double best = -999;

        for (WebElement form : forms) {

            double s = scoreForm(form, driver, state);

            if (s > best) best = s;
        }

        return best;
    }

    private static double scoreForm(WebElement form,
                                    WebDriver driver,
                                    State state) {

        try {

            if (!form.isDisplayed()) return -50;

            int inputs = form.findElements(By.cssSelector("input,textarea")).size();

            double score = inputs * 2;

            score += 10; // forms are important

            score += predictProgress(driver, state);

            return score;

        } catch (Exception e) {
            return -1;
        }
    }

    // ===================================================
    // 🌍 NAVIGATION SCORING
    // ===================================================
    private static double scoreBestNavigation(WebDriver driver,
                                              State state) {

        List<WebElement> links =
                driver.findElements(By.xpath("//a[@href]"));

        double best = -999;

        for (WebElement link : links) {

            try {

                String href = safe(link.getAttribute("href")).toLowerCase();

                double score = 0;

                if (href.contains("detail")) score += 8;
                if (href.contains("view")) score += 6;
                if (href.contains("edit")) score += 7;
                if (href.contains("form")) score += 10;

                if (href.contains("logout")) score -= 20;

                score += predictProgress(driver, state);

                if (score > best) best = score;

            } catch (Exception ignored) {}
        }

        return best;
    }

    // ===================================================
    // 💣 PROGRESS PREDICTION
    // ===================================================
    private static double predictProgress(WebDriver driver,
                                          State state) {

        try {

            int dom = driver.findElements(By.xpath("//*")).size();

            double score = 0;

            if (dom > 500) score += 2;
            else if (dom > 200) score += 1;

            score += state.depth * 0.5;

            if (state.pageType.contains("form")) score += 2;
            if (state.pageType.contains("detail")) score += 1;

            return score;

        } catch (Exception e) {
            return 0.5;
        }
    }

    // ===================================================
    // 🧠 SCENARIO BOOST
    // ===================================================
    private static double scenarioBoost(State state,
                                        String action) {

        String page = state.pageType.toLowerCase();

        double boost = 0;

        if (page.contains("login") && action.equals("FormAction"))
            boost += 25;

        if (page.contains("form") && action.equals("FormAction"))
            boost += 20;

        if (page.contains("detail") && action.equals("ClickAction"))
            boost += 12;

        return boost;
    }

    // ===================================================
    // 💣 PATH AWARENESS
    // ===================================================
    private static double pathAwareness() {

        List<String> path = SessionState.getNavigationPath();

        if (path.size() < 3) return 0;

        String last = path.get(path.size() - 1);

        int freq = Collections.frequency(path, last);

        if (freq > 2) {
            return -20; // loop detected
        }

        return 0;
    }

    // ===================================================
    private static String safe(String s) {
        return s == null ? "" : s;
    }
}