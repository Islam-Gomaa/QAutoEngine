package engine.goal;

import engine.context.ContextEngine;

import java.util.*;

public class GoalEngine {

    public enum GoalType {
        EXPLORE,
        ADD_TO_CART,
        REACH_CHECKOUT,
        COMPLETE_PAYMENT
    }

    private static GoalType currentGoal = GoalType.EXPLORE;

    private static final Set<GoalType> completedGoals = new HashSet<>();

    // 🔥 tracking
    private static int stepsOnSameGoal = 0;
    private static final int MAX_STEPS_PER_GOAL = 5;

    // ===================================================
    // 🎯 SET GOAL
    // ===================================================
    public static void setGoal(GoalType goal) {

        if (goal == currentGoal) return;

        currentGoal = goal;
        stepsOnSameGoal = 0;

        System.out.println("🎯 Goal switched → " + goal);
    }

    public static GoalType getGoal() {
        return currentGoal;
    }

    // ===================================================
    // 🔄 RESET (مهم جدًا)
    // ===================================================
    public static void reset() {

        currentGoal = GoalType.EXPLORE;
        completedGoals.clear();
        stepsOnSameGoal = 0;

        System.out.println("🔄 GoalEngine Reset");
    }

    // ===================================================
    // ✅ CHECK GOAL
    // ===================================================
    public static boolean isGoalReached(ContextEngine.ContextType context) {

        switch (currentGoal) {

            case ADD_TO_CART:
                return context == ContextEngine.ContextType.CART;

            case REACH_CHECKOUT:
                return context == ContextEngine.ContextType.CHECKOUT;

            case COMPLETE_PAYMENT:
                return context == ContextEngine.ContextType.PAYMENT;

            case EXPLORE:
            default:
                return false;
        }
    }

    // ===================================================
    // 🧠 MAIN BRAIN (🔥 أهم ميثود)
    // ===================================================
    public static void update(ContextEngine.ContextType context) {

        stepsOnSameGoal++;

        // ===================================================
        // ✅ Goal Achieved
        // ===================================================
        if (isGoalReached(context)) {

            completedGoals.add(currentGoal);

            System.out.println("✅ Goal achieved → " + currentGoal);

            moveToNextGoal();
            return;
        }

        // ===================================================
        // 💀 STUCK DETECTION
        // ===================================================
        if (stepsOnSameGoal >= MAX_STEPS_PER_GOAL) {

            System.out.println("⚠️ Stuck on goal → " + currentGoal);

            fallback();
        }

        // ===================================================
        // 🧠 SMART ENTRY LOGIC
        // ===================================================
        smartEntry(context);
    }

    // ===================================================
    // 🚀 NEXT GOAL
    // ===================================================
    private static void moveToNextGoal() {

        switch (currentGoal) {

            case EXPLORE:
                currentGoal = GoalType.ADD_TO_CART;
                break;

            case ADD_TO_CART:
                currentGoal = GoalType.REACH_CHECKOUT;
                break;

            case REACH_CHECKOUT:
                currentGoal = GoalType.COMPLETE_PAYMENT;
                break;

            case COMPLETE_PAYMENT:
                System.out.println("🏁 Journey Completed!");
                return;
        }

        stepsOnSameGoal = 0;

        System.out.println("🚀 Next Goal → " + currentGoal);
    }

    // ===================================================
    // 🔁 FALLBACK
    // ===================================================
    private static void fallback() {

        stepsOnSameGoal = 0;

        // 🔥 رجوع ذكي
        switch (currentGoal) {

            case COMPLETE_PAYMENT:
                currentGoal = GoalType.REACH_CHECKOUT;
                break;

            case REACH_CHECKOUT:
                currentGoal = GoalType.ADD_TO_CART;
                break;

            default:
                currentGoal = GoalType.EXPLORE;
        }

        System.out.println("🔁 Fallback → " + currentGoal);
    }

    // ===================================================
    // 🧠 SMART ENTRY
    // ===================================================
    private static void smartEntry(ContextEngine.ContextType context) {

        // لو دخلنا صفحة checkout فجأة
        if (context == ContextEngine.ContextType.CHECKOUT &&
                currentGoal != GoalType.COMPLETE_PAYMENT) {

            setGoal(GoalType.COMPLETE_PAYMENT);
        }

        // لو دخلنا cart فجأة
        if (context == ContextEngine.ContextType.CART &&
                currentGoal == GoalType.EXPLORE) {

            setGoal(GoalType.REACH_CHECKOUT);
        }
    }

    // ===================================================
    public static boolean isCompleted(GoalType goal) {
        return completedGoals.contains(goal);
    }

    public static Set<GoalType> getCompletedGoals() {
        return Collections.unmodifiableSet(completedGoals);
    }
}