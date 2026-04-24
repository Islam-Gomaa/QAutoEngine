package engine.decider;

import engine.actions.Action;
import engine.context.ContextEngine;
import engine.goal.GoalEngine;
import engine.learning.BehaviorGraph;
import engine.learning.BehaviorStore;
import engine.learning.LearningEngine;
import engine.state.SessionState;

import java.util.Optional;

public class ActionDecider {

    private static final int MAX_REPEAT = 3;
    private static final int MAX_FAILURE = 2;

    // ===================================================
    // 🧠 MAIN DECISION
    // ===================================================
    public static boolean shouldExecute(Action action) {

        if (action == null) return false;

        String actionName = action.getClass().getSimpleName();

        // ===================================================
        // 📊 MEMORY
        // ===================================================
        int executedCount = SessionState.getActionCount(actionName);
        int failureCount = SessionState.getFailureCount(actionName);

        // ===================================================
        // 🧠 CONTEXT + GOAL
        // ===================================================
        ContextEngine.ContextType context =
                ContextEngine.detect(SessionState.getCurrentUrl());

        GoalEngine.GoalType goal = GoalEngine.getGoal();

        // ===================================================
        // 🧠 BEHAVIOR GRAPH (priority 1 🔥)
        // ===================================================
        Optional<String> suggested =
                BehaviorGraph.suggest(SessionState.getCurrentUrl());

        if (suggested.isPresent() && suggested.get().equals(actionName)) {
            System.out.println("🧠 Graph prefers: " + actionName);
            return true;
        }

        // ===================================================
        // 🚨 STUCK DETECTION
        // ===================================================
        if (SessionState.isStuck()) {
            System.out.println("⚠️ Stuck → forcing navigation");
            return actionName.toLowerCase().contains("navigation");
        }

        // ===================================================
        // ❌ FAILURE GUARD
        // ===================================================
        if (failureCount >= MAX_FAILURE) {
            System.out.println("🚫 Too many failures: " + actionName);
            return false;
        }

        // ===================================================
        // 🔁 REPEAT GUARD
        // ===================================================
        if (executedCount >= MAX_REPEAT) {
            System.out.println("⚠️ Too repetitive: " + actionName);
            return false;
        }

        // ===================================================
        // 🧠 LEARNING BLOCK
        // ===================================================
        if (!LearningEngine.isActionAllowed(actionName)) {
            System.out.println("🚫 Blocked by Learning: " + actionName);
            return false;
        }

        // ===================================================
        // 🎯 GOAL-DRIVEN (priority 2 🔥)
        // ===================================================
        if (goal != null) {

            switch (goal) {

                case ADD_TO_CART:
                    if (context == ContextEngine.ContextType.PRODUCT &&
                            actionName.toLowerCase().contains("click")) {
                        System.out.println("🎯 Goal: Add to cart");
                        return true;
                    }
                    break;

                case REACH_CHECKOUT:
                    if (context == ContextEngine.ContextType.CART &&
                            actionName.toLowerCase().contains("navigation")) {
                        System.out.println("🎯 Goal: Checkout");
                        return true;
                    }
                    break;

                case COMPLETE_PAYMENT:
                    if (context == ContextEngine.ContextType.CHECKOUT &&
                            actionName.toLowerCase().contains("form")) {
                        System.out.println("🎯 Goal: Payment");
                        return true;
                    }
                    break;
            }
        }

        // ===================================================
        // 🧠 CONTEXT PRIORITY (priority 3)
        // ===================================================
        if (context == ContextEngine.ContextType.PRODUCT &&
                actionName.toLowerCase().contains("click")) return true;

        if (context == ContextEngine.ContextType.CART &&
                actionName.toLowerCase().contains("navigation")) return true;

        if (context == ContextEngine.ContextType.CHECKOUT &&
                actionName.toLowerCase().contains("form")) return true;

        // ===================================================
        // 📊 BEHAVIOR SCORE (priority 4)
        // ===================================================
        double score = BehaviorStore.getScore(actionName);

        if (score > 0.7) {
            System.out.println("📈 High score: " + actionName);
            return true;
        }

        if (score < 0.3) {
            System.out.println("🚫 Low score: " + actionName);
            return false;
        }

        // ===================================================
        // 🎯 DEFAULT IMPORTANT
        // ===================================================
        if (isImportant(actionName)) {
            return true;
        }

        // ===================================================
        // 🎲 SMART EXPLORATION
        // ===================================================
        return Math.random() > 0.3;
    }

    // ===================================================
    // 🧠 IMPORTANCE
    // ===================================================
    private static boolean isImportant(String actionName) {

        actionName = actionName.toLowerCase();

        return actionName.contains("navigation") ||
                actionName.contains("click") ||
                actionName.contains("form");
    }
}