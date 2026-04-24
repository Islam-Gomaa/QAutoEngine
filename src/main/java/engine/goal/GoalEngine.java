package engine.goal;

import engine.context.ContextEngine;

import java.util.HashSet;
import java.util.Set;

public class GoalEngine {

    public enum GoalType {
        EXPLORE,
        ADD_TO_CART,
        REACH_CHECKOUT,
        COMPLETE_PAYMENT
    }

    private static GoalType currentGoal = GoalType.EXPLORE;

    private static final Set<GoalType> completedGoals = new HashSet<>();

    // ===================================================
    // 🎯 SET GOAL
    // ===================================================
    public static void setGoal(GoalType goal) {
        currentGoal = goal;
        System.out.println("🎯 Current Goal → " + goal);
    }

    public static GoalType getGoal() {
        return currentGoal;
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
    // 🔄 AUTO PROGRESSION (🔥 ذكي)
    // ===================================================
    public static void updateProgress(ContextEngine.ContextType context) {

        if (isGoalReached(context)) {

            completedGoals.add(currentGoal);

            System.out.println("✅ Goal achieved: " + currentGoal);

            // 🔥 move to next goal
            switch (currentGoal) {

                case ADD_TO_CART:
                    currentGoal = GoalType.REACH_CHECKOUT;
                    break;

                case REACH_CHECKOUT:
                    currentGoal = GoalType.COMPLETE_PAYMENT;
                    break;

                default:
                    break;
            }
        }
    }

    public static boolean isCompleted(GoalType goal) {
        return completedGoals.contains(goal);
    }
}