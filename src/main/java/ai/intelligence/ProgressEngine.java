package ai.intelligence;

import ai.goal.GoalEngine;
import ai.learning.State;
import ai.planning.Plan;
import ai.planning.PlanningEngine;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.List;

public class ProgressEngine {

    // ===================================================
    // 🧠 MAIN PROGRESS SCORE (PURE V6)
    // ===================================================
    public static double evaluateProgress(
            WebDriver driver,
            State prev,
            State next) {

        if (next == null) {
            return -2;
        }

        double score = 0;

        // ===============================================
        // URL CHANGE
        // ===============================================
        if (prev != null
                && !safe(prev.url)
                .equals(safe(next.url))) {

            score += 2.5;
        }

        // ===============================================
        // URL DEPTH
        // ===============================================
        int beforeDepth =
                getDepth(prev != null
                        ? prev.url
                        : null);

        int afterDepth =
                getDepth(next.url);

        if (afterDepth > beforeDepth) {
            score += 1.5;
        } else if (afterDepth < beforeDepth) {
            score -= 1;
        }

        // ===============================================
        // DOM CHANGE
        // ===============================================
        int beforeDom =
                prev != null
                        ? prev.domSize
                        : 0;

        int delta =
                Math.abs(
                        next.domSize
                                - beforeDom
                );

        if (delta > 50) {
            score += 2;
        } else if (delta > 10) {
            score += 1;
        }

        // ===============================================
        // STATE CHANGE
        // ===============================================
        if (prev != null
                && !prev.equals(next)) {

            score += 2;

        } else {

            score -= 1.5;
        }

        // ===============================================
        // CONTEXT SIGNALS
        // ===============================================
        score += contextSignals(next);

        // ===============================================
        // GOAL ALIGNMENT
        // ===============================================
        score += goalAlignment(next);

        // ===============================================
        // PLAN ALIGNMENT
        // ===============================================
        score += planAlignment(next);

        // ===============================================
        // URL SEMANTICS
        // ===============================================
        score += semanticUrlScore(
                next.url
        );

        return clamp(
                score,
                -5,
                10
        );
    }

    // ===================================================
    // 🧠 TERMINAL DETECTION
    // ===================================================
    public static boolean isTerminal(
            WebDriver driver,
            State state) {

        try {

            String page =
                    driver
                            .getPageSource()
                            .toLowerCase();

            // success signals
            if (page.contains("success")
                    || page.contains("completed")
                    || page.contains("thank you")
                    || page.contains("done")
                    || page.contains("order confirmed")) {

                return true;
            }

            // no interactions
            List<WebElement> clickable =
                    driver.findElements(
                            By.xpath(
                                    "//a | //button"
                            )
                    );

            if (clickable.isEmpty()) {
                return true;
            }

            // dead page
            return state != null
                    && !state.hasForm
                    && !state.hasLinks;

        } catch (Exception e) {

            return false;
        }
    }

    // ===================================================
    // 🧠 STUCK DETECTION
    // ===================================================
    public static boolean isStuck(
            int stepsWithoutProgress) {

        return stepsWithoutProgress >= 4;
    }

    // ===================================================
    // 🧠 GOAL ALIGNMENT
    // ===================================================
    private static double goalAlignment(
            State state) {

        if (state == null
                || GoalEngine
                .getCurrentGoal()
                == null) {

            return 0;
        }

        String goal =
                GoalEngine
                        .getCurrentGoal()
                        .name;

        return state.goal
                .contains(goal)
                ? 2
                : 0;
    }

    // ===================================================
    // 🧠 PLAN ALIGNMENT
    // ===================================================
    private static double planAlignment(
            State state) {

        Plan plan =
                PlanningEngine
                        .getCurrentPlan();

        if (plan == null
                || plan.getCurrentStep()
                == null) {

            return 0;
        }

        String step =
                plan.getCurrentStep();

        switch (step) {

            case "FormAction":
                return state.hasForm
                        ? 2
                        : -1;

            case "NavigationAction":
                return state.hasLinks
                        ? 1.5
                        : 0;

            case "ClickAction":
                return state.hasLinks
                        ? 1
                        : 0;
        }

        return 0;
    }

    // ===================================================
    // 🧠 CONTEXT SIGNALS
    // ===================================================
    private static double contextSignals(
            State state) {

        if (state == null) {
            return 0;
        }

        double score = 0;

        if (state.hasForm) {
            score += 1.5;
        }

        if (state.hasLinks) {
            score += 1;
        }

        return score;
    }

    // ===================================================
    // 🔗 URL SEMANTICS
    // ===================================================
    private static double semanticUrlScore(
            String url) {

        if (url == null) {
            return 0;
        }

        url = url.toLowerCase();

        double score = 0;

        if (url.contains("detail"))
            score += 1;

        if (url.contains("view"))
            score += 1;

        if (url.contains("edit"))
            score += 1.5;

        if (url.contains("create"))
            score += 1.5;

        if (url.contains("form"))
            score += 2;

        if (url.contains("submit"))
            score += 2.5;

        if (url.contains("confirm"))
            score += 3;

        return score;
    }

    // ===================================================
    private static int getDepth(
            String url) {

        if (url == null
                || url.isEmpty()) {

            return 0;
        }

        return url.split("/")
                .length;
    }

    private static String safe(
            String s) {

        return s == null
                ? ""
                : s;
    }

    private static double clamp(
            double v,
            double min,
            double max) {

        return Math.max(
                min,
                Math.min(max, v)
        );
    }
}