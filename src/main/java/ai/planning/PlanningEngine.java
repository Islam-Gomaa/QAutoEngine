package ai.planning;

import ai.goal.GoalEngine;
import ai.goal.GoalEngine.Goal;

import org.openqa.selenium.WebDriver;

public class PlanningEngine {

    private static Plan currentPlan;

    // ===================================================
    // 🧠 BUILD PLAN (MCTS DRIVEN 💣)
    // ===================================================
    public static Plan build(WebDriver driver) {

        Goal goal = GoalEngine.getCurrentGoal();

        Plan plan = new Plan(goal.name);

        // 💣 AI decides next action
        String action = MCTSPlanner.plan(driver);

        plan.steps.add(action);

        plan.confidence = 1.0;
        plan.stability = 1.0;

        currentPlan = plan;

        System.out.println("🧠 PLAN → " + action);

        return plan;
    }

    // ===================================================
    // 🔁 UPDATE
    // ===================================================
    public static void update(boolean success, WebDriver driver) {

        if (currentPlan == null) return;

        if (success) {
            currentPlan.next();
        } else {
            currentPlan.fail();
        }

        // 💣 replan conditions
        if (shouldReplan()) {
            System.out.println("🔁 REPLAN");
            build(driver);
        }
    }

    // ===================================================
    private static boolean shouldReplan() {

        if (currentPlan.failed) return true;

        if (currentPlan.confidence < 0.3) return true;

        return false;
    }

    // ===================================================
    public static Plan getCurrentPlan() {
        return currentPlan;
    }

    public static void reset() {
        currentPlan = null;
    }
}