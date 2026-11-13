package ai.planning;

import ai.goal.GoalEngine;
import ai.goal.GoalEngine.Goal;

import org.openqa.selenium.WebDriver;

public class PlanningEngine {

    private static Plan currentPlan;

    // ===================================================
    // 🧠 BUILD PLAN
    // ===================================================
    public static Plan build(WebDriver driver) {

        Goal goal =
                GoalEngine
                        .getCurrentGoal();

        if (goal == null) {

            System.out.println(
                    "⚠️ No goal found"
            );

            return null;
        }

        Plan plan =
                new Plan(goal.name);

        // ===============================================
        // 💣 AI PLANNING
        // ===============================================
        String action =
                MCTSPlanner.plan(driver);

        // fallback
        if (action == null
                || action.isBlank()) {

            action =
                    fallbackAction(goal);
        }

        plan.addStep(action);

        currentPlan = plan;

        System.out.println(
                "🧠 PLAN → "
                        + action
                        + " | goal="
                        + goal.name
        );

        return plan;
    }

    // ===================================================
    // 🔁 UPDATE
    // ===================================================
    public static void update(
            boolean success,
            WebDriver driver) {

        if (currentPlan == null) {
            return;
        }

        if (success) {

            currentPlan.success();
            currentPlan.next();

        } else {

            currentPlan.fail();
        }

        // ===============================================
        // 💣 REPLAN CONDITIONS
        // ===============================================
        if (shouldReplan()) {

            System.out.println(
                    "🔁 REPLAN"
            );

            build(driver);
        }
    }

    // ===================================================
    // 🧠 REPLAN LOGIC
    // ===================================================
    private static boolean shouldReplan() {

        if (currentPlan == null) {
            return false;
        }

        // failed plan
        if (currentPlan.isFailed()) {
            return true;
        }

        // weak confidence
        if (currentPlan
                .getConfidence()
                < 0.3) {

            return true;
        }

        // unstable
        if (currentPlan
                .getStability()
                < 0.2) {

            return true;
        }

        return false;
    }

    // ===================================================
    // 🧠 FALLBACK
    // ===================================================
    private static String fallbackAction(
            Goal goal) {

        if (goal == null) {
            return "NavigationAction";
        }

        return switch (
                goal.name
                ) {

            case "AUTH" ->
                    "FormAction";

            case "FORM" ->
                    "FormAction";

            case "SEARCH" ->
                    "ClickAction";

            case "NAVIGATION" ->
                    "NavigationAction";

            default ->
                    "NavigationAction";
        };
    }

    // ===================================================
    // 🧠 GETTERS
    // ===================================================
    public static Plan getCurrentPlan() {
        return currentPlan;
    }

    public static boolean hasPlan() {

        return currentPlan != null
                && !currentPlan.isCompleted()
                && !currentPlan.isFailed();
    }

    // ===================================================
    // 🧹 RESET
    // ===================================================
    public static void reset() {
        currentPlan = null;
    }
}