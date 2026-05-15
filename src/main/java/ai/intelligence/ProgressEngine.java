package ai.intelligence;

import ai.goal.GoalEngine;
import ai.planning.PlanningEngine;
import ai.planning.Plan;
import ai.learning.State;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.List;

public class ProgressEngine {

    // ===================================================
    // 🧠 MAIN PROGRESS SCORE (V6)
    // ===================================================
    public static double evaluateProgress(WebDriver driver,
                                          State prev,
                                          State next,
                                          String beforeUrl,
                                          String afterUrl,
                                          int beforeDom,
                                          int afterDom) {

        double score = 0;

        // ================= URL CHANGE =================
        if (!safe(beforeUrl).equals(safe(afterUrl))) {
            score += 2.5;
        }

        // ================= URL DEPTH =================
        int beforeDepth = getDepth(beforeUrl);
        int afterDepth = getDepth(afterUrl);

        if (afterDepth > beforeDepth) score += 1.5;
        else if (afterDepth < beforeDepth) score -= 1; // 🔥 regression

        // ================= DOM CHANGE =================
        int delta = Math.abs(afterDom - beforeDom);

        if (delta > 50) score += 2;
        else if (delta > 10) score += 1;

        // ================= STATE CHANGE (V6) =================
        if (prev != null && next != null &&
                !prev.signature().equals(next.signature())) {

            score += 2;
        } else {
            score -= 1.5; // 🔥 no change = bad
        }

        // ================= CONTEXT SIGNALS =================
        score += contextSignals(next);

        // ================= GOAL ALIGNMENT =================
        score += goalAlignment(next);

        // ================= PLAN ALIGNMENT =================
        score += planAlignment(next);

        // ================= SEMANTIC URL =================
        score += semanticUrlScore(afterUrl);

        // ================= NORMALIZATION =================
        return clamp(score, -5, 10);
    }

    // ===================================================
    // 🧠 GOAL ALIGNMENT
    // ===================================================
    private static double goalAlignment(State state) {

        if (state == null || GoalEngine.getCurrentGoal() == null) return 0;

        String goal = GoalEngine.getCurrentGoal().name;

        if (state.goal.contains(goal)) return 2;

        return 0;
    }

    // ===================================================
    // 🧠 PLAN ALIGNMENT
    // ===================================================
    private static double planAlignment(State state) {

        Plan plan = PlanningEngine.getCurrentPlan();

        if (plan == null || plan.getCurrentStep() == null) return 0;

        String step = plan.getCurrentStep();

        switch (step) {

            case "FILL_FORM":
                return state.hasForm ? 2 : -1;

            case "NAVIGATE":
                return state.hasLinks ? 1.5 : 0;

            case "SUBMIT":
                return state.goal.contains("FORM") ? 1.5 : 0;
        }

        return 0;
    }

    // ===================================================
    // 🧠 CONTEXT SIGNALS
    // ===================================================
    private static double contextSignals(State state) {

        if (state == null) return 0;

        double score = 0;

        if (state.hasForm) score += 1.5;
        if (state.hasLinks) score += 1;

        return score;
    }

    // ===================================================
    // 🧠 TERMINAL DETECTION (UPGRADED)
    // ===================================================
    public static boolean isTerminal(WebDriver driver, State state) {

        String page = driver.getPageSource().toLowerCase();

        // success indicators
        if (page.contains("success")
                || page.contains("completed")
                || page.contains("thank you")
                || page.contains("done")
                || page.contains("order confirmed")) {
            return true;
        }

        // no interaction left
        List<WebElement> clickable =
                driver.findElements(By.xpath("//a | //button"));

        if (clickable.isEmpty()) return true;

        // no forms + no links → dead end
        if (state != null && !state.hasForm && !state.hasLinks)
            return true;

        return false;
    }

    // ===================================================
    // 🧠 STAGNATION DETECTION
    // ===================================================
    public static boolean isStuck(int stepsWithoutProgress) {
        return stepsWithoutProgress >= 4;
    }

    // ===================================================
    // 🔗 URL SEMANTICS
    // ===================================================
    private static double semanticUrlScore(String url) {

        if (url == null) return 0;

        url = url.toLowerCase();

        double score = 0;

        if (url.contains("detail")) score += 1;
        if (url.contains("view")) score += 1;
        if (url.contains("edit")) score += 1.5;
        if (url.contains("create")) score += 1.5;
        if (url.contains("form")) score += 2;
        if (url.contains("submit")) score += 2.5;
        if (url.contains("confirm")) score += 3;

        return score;
    }

    // ===================================================
    private static int getDepth(String url) {

        if (url == null || url.isEmpty()) return 0;

        return url.split("/").length;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}