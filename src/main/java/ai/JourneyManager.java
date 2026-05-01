package engine.ai;

import engine.context.ContextEngine;
import engine.goal.GoalEngine;
import engine.goal.GoalEngine.GoalType;

import org.openqa.selenium.WebDriver;

import java.util.HashMap;
import java.util.Map;

public class JourneyManager {

    // 🔥 tracking
    private static int stepCount = 0;
    private static int lastProgressStep = 0;

    private static final int MAX_STEPS_WITHOUT_PROGRESS = 4;

    private static GoalType lastGoal = GoalType.EXPLORE;

    private static final Map<GoalType, Integer> goalAttempts = new HashMap<>();

    // ===================================================
    // 🧠 MAIN ENTRY
    // ===================================================
    public static void update(WebDriver driver) {

        stepCount++;

        ContextEngine.ContextType context = ContextEngine.detect(driver);
        GoalType currentGoal = GoalEngine.getGoal();

        System.out.println("🧠 Journey Step: " + stepCount +
                " | Context: " + context +
                " | Goal: " + currentGoal);

        // ===================================================
        // ✅ Progress check
        // ===================================================
        if (GoalEngine.isGoalReached(context)) {

            System.out.println("✅ Goal Achieved → " + currentGoal);

            GoalEngine.update(context);

            lastProgressStep = stepCount;
            lastGoal = currentGoal;

            return;
        }

        // ===================================================
        // 💀 STUCK DETECTION
        // ===================================================
        if (stepCount - lastProgressStep > MAX_STEPS_WITHOUT_PROGRESS) {

            System.out.println("⚠️ Stuck detected on → " + currentGoal);

            handleStuck(currentGoal);

            lastProgressStep = stepCount;
            return;
        }

        // ===================================================
        // 🧠 SMART ENTRY
        // ===================================================
        smartEntry(context, currentGoal);
    }

    // ===================================================
    // 🔥 HANDLE STUCK
    // ===================================================
    private static void handleStuck(GoalType goal) {

        int attempts = goalAttempts.getOrDefault(goal, 0) + 1;
        goalAttempts.put(goal, attempts);

        System.out.println("🔁 Retry count for " + goal + ": " + attempts);

        // 🔥 retry limit
        if (attempts >= 2) {

            System.out.println("💀 Goal failed → fallback");

            fallback(goal);

            goalAttempts.put(goal, 0);
        }
    }

    // ===================================================
    // 🔁 FALLBACK LOGIC
    // ===================================================
    private static void fallback(GoalType goal) {

        switch (goal) {

            case COMPLETE_PAYMENT:
                GoalEngine.setGoal(GoalType.REACH_CHECKOUT);
                break;

            case REACH_CHECKOUT:
                GoalEngine.setGoal(GoalType.ADD_TO_CART);
                break;

            case ADD_TO_CART:
                GoalEngine.setGoal(GoalType.EXPLORE);
                break;

            default:
                GoalEngine.setGoal(GoalType.EXPLORE);
        }
    }

    // ===================================================
    // 🧠 SMART ENTRY (Context overrides)
    // ===================================================
    private static void smartEntry(ContextEngine.ContextType context,
                                   GoalType currentGoal) {

        switch (context) {

            case PRODUCT:
                if (currentGoal == GoalType.EXPLORE) {
                    GoalEngine.setGoal(GoalType.ADD_TO_CART);
                }
                break;

            case CART:
                if (currentGoal != GoalType.COMPLETE_PAYMENT) {
                    GoalEngine.setGoal(GoalType.REACH_CHECKOUT);
                }
                break;

            case CHECKOUT:
                GoalEngine.setGoal(GoalType.COMPLETE_PAYMENT);
                break;

            case PAYMENT:
                System.out.println("🏁 Payment Page Reached → Journey Complete");
                break;

            default:
                break;
        }
    }

    // ===================================================
    // 🔄 RESET (مهم جدًا)
    // ===================================================
    public static void reset() {

        stepCount = 0;
        lastProgressStep = 0;
        lastGoal = GoalType.EXPLORE;
        goalAttempts.clear();

        System.out.println("🔄 JourneyManager Reset");
    }
}