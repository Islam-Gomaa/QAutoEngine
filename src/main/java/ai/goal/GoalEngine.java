package ai.goal;

import ai.learning.BehaviorStore;
import ai.learning.State;
import ai.planning.PlanningEngine;
import ai.planning.Plan;
import engine.state.SessionState;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import java.util.*;

public class GoalEngine {

    // ===================================================
    // 🧠 GOAL MODEL (V6)
    // ===================================================
    public static class Goal {

        public final String name;

        public double desirability;
        public double confidence;
        public double progress;
        public double urgency;
        public double stability;

        public boolean completed;

        public Goal(String name) {
            this.name = name;
        }

        public double score() {
            return desirability * 0.25
                    + confidence * 0.2
                    + progress * 0.25
                    + urgency * 0.1
                    + stability * 0.2;
        }

        @Override
        public String toString() {
            return name +
                    " | score=" + score() +
                    " | progress=" + progress;
        }
    }

    // ===================================================
    private static final Map<String, Goal> goals = new HashMap<>();
    private static Goal currentGoal;
    private static Goal lastGoal;

    // ===================================================
    public static void init() {

        goals.clear();

        add("AUTH");
        add("FORM");
        add("SEARCH");
        add("NAVIGATION");
        add("EXPLORE");

        currentGoal = goals.get("EXPLORE");
        lastGoal = currentGoal;
    }

    private static void add(String name) {
        goals.put(name, new Goal(name));
    }

    // ===================================================
    // 🧠 MAIN UPDATE (STATE-DRIVEN 💣)
    // ===================================================
    public static void update(WebDriver driver) {

        State state = buildState(driver);

        Plan plan = PlanningEngine.getCurrentPlan();

        for (Goal g : goals.values()) {

            g.progress = estimateProgress(g, driver);
            g.desirability = estimateValue(g, state);
            g.confidence = estimateConfidence(state);
            g.urgency = estimateUrgency(state);
            g.stability = stabilityBoost(g);

            // 🔥 PLAN AWARENESS
            if (plan != null && plan.goal != null && plan.goal.equals(g.name)) {
                g.progress += 1.5;
                g.confidence += 0.5;
            }

            if (g.progress >= 6) {
                g.completed = true;
            }
        }

        lastGoal = currentGoal;

        currentGoal = goals.values().stream()
                .filter(g -> !g.completed)
                .max(Comparator.comparingDouble(Goal::score))
                .orElse(goals.get("EXPLORE"));

        System.out.println("🎯 Goal → " + currentGoal);
    }

    // ===================================================
    // 🔥 PROGRESS
    // ===================================================
    private static double estimateProgress(Goal g, WebDriver d) {

        String url = d.getCurrentUrl().toLowerCase();
        String page = d.getPageSource().toLowerCase();

        double score = 0;

        switch (g.name) {

            case "AUTH":
                if (page.contains("password")) score += 2;
                if (page.contains("login")) score += 2;
                if (url.contains("dashboard")) score += 4;
                break;

            case "FORM":
                int inputs = d.findElements(By.tagName("input")).size();
                score += inputs * 0.2;
                if (page.contains("submit")) score += 1;
                break;

            case "SEARCH":
                if (page.contains("search")) score += 2;
                break;

            case "NAVIGATION":
                int links = d.findElements(By.tagName("a")).size();
                score += links * 0.1;
                break;

            case "EXPLORE":
                score += 1;
                break;
        }

        return score;
    }

    // ===================================================
    // 💣 VALUE (FIXED)
    // ===================================================
    private static double estimateValue(Goal g, State state) {

        double learned = BehaviorStore.getScore(state, g.name);

        return 1 + learned * 3;
    }

    // ===================================================
    // 💣 CONFIDENCE (FIXED)
    // ===================================================
    private static double estimateConfidence(State state) {

        double reward = SessionState.getReward(state);

        return Math.max(0.5, Math.min(3, 1 + reward));
    }

    // ===================================================
    // 💣 URGENCY (FIXED)
    // ===================================================
    private static double estimateUrgency(State state) {

        int visits = SessionState.getStateVisitCount(state);

        if (visits > 5) return -2;
        if (visits > 2) return -1;

        return 1;
    }

    // ===================================================
    private static double stabilityBoost(Goal g) {

        if (lastGoal == null) return 0;

        if (g.name.equals(lastGoal.name)) return 2;

        return -0.5;
    }

    // ===================================================
    // 💣 STATE BUILDER
    // ===================================================
    private static State buildState(WebDriver driver) {

        int domSize = driver.findElements(By.xpath("//*")).size();

        boolean hasForm = !driver.findElements(By.tagName("form")).isEmpty();
        boolean hasLinks = !driver.findElements(By.tagName("a")).isEmpty();

        return new State(
                driver.getCurrentUrl(),
                domSize,
                "GOAL",
                hasForm,
                hasLinks
        );
    }

    // ===================================================
    public static Goal getCurrentGoal() {
        return currentGoal;
    }

    public static void reset() {
        init();
    }
}