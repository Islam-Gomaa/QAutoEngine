package ai.decision;

import ai.goal.GoalEngine;
import ai.goal.GoalEngine.Goal;
import ai.learning.LearningEngine;
import ai.planning.MCTSPlanner;
import ai.planning.PlanningEngine;
import ai.planning.Plan;
import ai.learning.BehaviorGraph;
import ai.learning.BehaviorStore;
import ai.learning.State;
import engine.state.SessionState;

import org.openqa.selenium.WebDriver;

import java.util.*;

public class DecisionEngine {

    private static final List<String> ACTIONS = List.of(
            "ClickAction",
            "FormAction",
            "NavigationAction"
    );

    // ===================================================
    // 🧠 MAIN ENTRY
    // ===================================================
    public static String decide(WebDriver driver, State state) {

        Goal goal = GoalEngine.getCurrentGoal();
        Plan plan = PlanningEngine.getCurrentPlan();
        String step = (plan != null) ? plan.getCurrentStep() : null;

        // 💣 أولاً: خليه يستفيد من MCTS
        String mctsSuggestion = MCTSPlanner.plan(driver);

        Map<String, Double> scores = new HashMap<>();

        for (String action : ACTIONS) {

            if (!isActionAllowed(action, step)) {
                scores.put(action, -100.0);
                continue;
            }

            double score = evaluate(action, step, goal, state);

            // 💣 boost لو MCTS اقترحه
            if (action.equals(mctsSuggestion)) {
                score += 15;
            }

            scores.put(action, score);

            System.out.println("➡️ " + action + " = " + score);
        }

        return scores.entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("NavigationAction");
    }

    // ===================================================
    // 💣 CORE EVALUATION
    // ===================================================
    private static double evaluate(String action,
                                   String step,
                                   Goal goal,
                                   State state) {

        double score = 0;

        // ===============================================
        // 🔥 BASE
        // ===============================================
        score += baseScore(action, state);

        // ===============================================
        // 🧠 LEARNING
        // ===============================================
        score += BehaviorStore.getScore(state, action) *8;
        score += BehaviorGraph.getActionScore(state, action) * 4;
        score += LearningEngine.getScore(state, action) * 6;
        // ===============================================
        // 💣 PLAN ALIGNMENT
        // ===============================================
        score += planAlignment(action, step);

        // ===============================================
        // 💣 GOAL ALIGNMENT
        // ===============================================
        score += goalAlignment(action, goal);

        // ===============================================
        // 💣 CONTEXT
        // ===============================================
        score += contextBoost(action, state);

        // ===============================================
        // 💣 MEMORY
        // ===============================================
        score += SessionState.getReward(state) * 2;

        // ===============================================
        // 💣 LOOP / FAILURE
        // ===============================================
        if (SessionState.isLoopingState(state)) score -= 20;

        if (SessionState.getFailureCount(action) > 2) score -= 10;

        if (SessionState.shouldAvoidState(state)) score -= 15;

        // ===============================================
        // 💣 FUTURE ESTIMATION (NEW 🔥)
        // ===============================================
        score += futureEstimate(action, state);

        // ===============================================
        return clamp(score, -50, 100);
    }

    // ===================================================
    // 💣 أهم إضافة
    // ===================================================
    private static double futureEstimate(String action, State state) {

        // simulate next state
        int newDom = state.domSize + new Random().nextInt(15) - 5;

        State next = new State(
                state.url,
                Math.max(1, newDom),
                state.goal,
                state.hasForm,
                state.hasLinks
        );

        double value = 0;

        value += BehaviorStore.getScore(next, action) * 5;
        value += SessionState.getReward(next);

        if (SessionState.shouldAvoidState(next)) value -= 10;

        return value;
    }

    // ===================================================
    private static double baseScore(String action, State state) {

        switch (action) {

            case "ClickAction":
                return state.hasLinks ? 5 : 0;

            case "FormAction":
                return state.hasForm ? 5 : 0;

            case "NavigationAction":
                return 3;
        }

        return 0;
    }

    // ===================================================
    private static double planAlignment(String action, String step) {

        if (step == null) return 0;

        switch (step) {

            case "FILL_FORM":
                return action.equals("FormAction") ? 50 : -20;

            case "SUBMIT":
                return action.equals("ClickAction") ? 45 : -15;

            case "NAVIGATE":
                return action.equals("NavigationAction") ? 40 : -10;

            case "CLICK_LOGIN":
            case "CLICK_LINK":
                return action.equals("ClickAction") ? 40 : -10;

            case "TYPE_QUERY":
                return action.equals("FormAction") ? 35 : -10;
        }

        return 0;
    }

    // ===================================================
    private static boolean isActionAllowed(String action, String step) {

        if (step == null) return true;

        switch (step) {

            case "FILL_FORM":
                return action.equals("FormAction");

            case "SUBMIT":
            case "CLICK_LOGIN":
                return action.equals("ClickAction");

            case "NAVIGATE":
                return action.equals("NavigationAction");
        }

        return true;
    }

    // ===================================================
    private static double goalAlignment(String action, Goal goal) {

        if (goal == null) return 0;

        switch (goal.name) {

            case "FORM":
                return action.equals("FormAction") ? 20 : 0;

            case "AUTH":
                return action.equals("FormAction") ? 15 : 0;

            case "NAVIGATION":
                return action.equals("NavigationAction") ? 15 : 0;
        }

        return 0;
    }

    // ===================================================
    private static double contextBoost(String action, State state) {

        double score = 0;

        if (action.equals("FormAction") && state.hasForm)
            score += 10;

        if (action.equals("NavigationAction") && state.hasLinks)
            score += 8;

        return score;
    }

    // ===================================================
    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}