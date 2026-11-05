package ai.learning;

import ai.goal.GoalEngine;
import ai.planning.PlanningEngine;
import ai.planning.Plan;

public class RewardEngine {

    // ===================================================
    // 🧠 MAIN REWARD FUNCTION (V6)
    // ===================================================
    public static double calculate(State prev,
                                   State next,
                                   String action,
                                   boolean success,
                                   boolean revisited,
                                   double progressScore,
                                   boolean terminal) {

        double reward = 0;

        // ===============================
        // 1️⃣ BASE
        // ===============================
        reward += success ? 2.0 : -2.5;

        // ===============================
        // 2️⃣ PROGRESS
        // ===============================
        reward += progressScore * 1.5;

        if (progressScore > 4) reward += 3;
        else if (progressScore > 2) reward += 1.5;

        // ===============================
        // 3️⃣ STATE CHANGE (V6 FIX)
        // ===============================
        if (prev != null && next != null &&
                !safe(prev.goal).equals(safe(next.goal))) {

            reward += 3;
        }

        // ===============================
        // 4️⃣ DOM CHANGE
        // ===============================
        int delta = Math.abs(next.domSize - prev.domSize);

        if (delta > 80) reward += 2;
        else if (delta > 30) reward += 1;

        // ===============================
        // 5️⃣ CONTEXT INTELLIGENCE 🔥
        // ===============================
        if (action.equals("FormAction") && next.hasForm)
            reward += 1.5;

        if (action.equals("NavigationAction") && next.hasLinks)
            reward += 1;

        // ===============================
        // 6️⃣ GOAL ALIGNMENT
        // ===============================
        if (GoalEngine.getCurrentGoal() != null &&
                next.goal.contains(GoalEngine.getCurrentGoal().name)) {

            reward += 2;
        }

        // ===============================
        // 7️⃣ PLAN ALIGNMENT
        // ===============================
        Plan plan = PlanningEngine.getCurrentPlan();

        if (plan != null && plan.getCurrentStep() != null) {

            String step = plan.getCurrentStep();

            if (matches(action, step)) reward += 2;
            else reward -= 1;
        }

        // ===============================
        // 8️⃣ LOOP PENALTY
        // ===============================
        if (revisited) reward -= 4;

        // ===============================
        // 9️⃣ STAGNATION
        // ===============================
        if (progressScore < 1) reward -= 2;

        // ===============================
        // 🔟 TERMINAL BONUS
        // ===============================
        if (terminal) reward += 12;

        // ===============================
        // 🔥 NORMALIZATION (SAFE)
        // ===============================
        return clamp(reward, -10, 10);
    }

    // ===================================================
    private static boolean matches(String action, String step) {

        if (step == null) return false;

        switch (step) {
            case "FILL_FORM":
                return action.equals("FormAction");

            case "SUBMIT":
                return action.equals("ClickAction");

            case "NAVIGATE":
                return action.equals("NavigationAction");
        }

        return true;
    }

    // ===================================================
    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}