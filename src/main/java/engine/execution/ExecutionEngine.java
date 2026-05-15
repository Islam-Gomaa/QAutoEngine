package engine.execution;

import ai.decision.DecisionEngine;
import ai.goal.GoalEngine;
import ai.planning.PlanningEngine;
import ai.planning.Plan;

import ai.learning.*;
import ai.intelligence.*;

import engine.actions.*;
import engine.state.SessionState;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

public class ExecutionEngine {

    private static final int MAX_STEPS = 60;

    public static void run(WebDriver driver) {

        System.out.println("🚀 AI Engine V6 START");

        GoalEngine.init();
        PlanningEngine.reset();
        ScenarioTracker.reset();
        LoopDetector.reset();
        SessionState.reset();
        LearningEngine.reset();

        int step = 0;
        int stagnation = 0;

        while (step++ < MAX_STEPS) {

            System.out.println("\n====================");
            System.out.println("STEP " + step);
            System.out.println("====================");

            // ===========================================
            // 🧠 STATE (BEFORE)
            // ===========================================
            State prevState = buildState(driver);
            SessionState.markStateVisited(prevState);

            // ===========================================
            // 🧠 GOAL
            // ===========================================
            GoalEngine.update(driver);

            // ===========================================
            // 🧠 PLAN
            // ===========================================
            Plan plan = PlanningEngine.getCurrentPlan();

            if (plan == null || plan.completed || plan.failed) {
                plan = PlanningEngine.build(GoalEngine.getCurrentGoal());
            }

            String currentStep = (plan != null) ? plan.getCurrentStep() : "NONE";

            System.out.println("📌 Goal → " + safeGoal());
            System.out.println("📌 Plan Step → " + currentStep);

            // ===========================================
            // 🧠 DECISION
            // ===========================================
            String action = DecisionEngine.decide(driver, prevState);
            System.out.println("🤖 Action → " + action);

            // ===========================================
            // ⚙️ EXECUTION
            // ===========================================
            boolean success = execute(action, driver);

            if (!success) {
                SessionState.recordFailure(action, prevState);
            }

            // ===========================================
            // 🧠 STATE (AFTER)
            // ===========================================
            State nextState = buildState(driver);

            // ===========================================
            // 🔁 LOOP DETECTION
            // ===========================================
            LoopDetector.record(nextState);
            boolean revisited = LoopDetector.isLooping(nextState);

            // ===========================================
            // 🧠 PROGRESS
            // ===========================================
            double progress = ProgressEngine.evaluateProgress(
                    driver,
                    prevState.url,
                    nextState.url,
                    prevState.domSize,
                    nextState.domSize
            );

            boolean terminal = ProgressEngine.isTerminal(driver);

            if (progress > 0) stagnation = 0;
            else stagnation++;

            // ===========================================
            // 🧠 REWARD (single source)
            // ===========================================
            double reward = RewardEngine.calculate(
                    prevState,
                    nextState,
                    action,
                    success,
                    revisited,
                    progress,
                    terminal
            );

            SessionState.reward(nextState, reward);

            // ===========================================
            // 🧠 LEARNING
            // ===========================================
            LearningEngine.learn(
                    prevState,
                    nextState,
                    action,
                    success,
                    reward
            );

            BehaviorStore.record(prevState, action, success, reward);

            BehaviorGraph.record(
                    prevState,
                    action,
                    nextState,
                    driver.getCurrentUrl(),
                    success,
                    reward
            );

            // ===========================================
            // 🧠 SCENARIO TRACK
            // ===========================================
            ScenarioTracker.record(
                    action,
                    nextState.url,
                    nextState,
                    progress
            );

            // ===========================================
            // 🧠 PLAN UPDATE
            // ===========================================
            PlanningEngine.update(success);

            // ===========================================
            // 🛑 EXIT CONDITIONS
            // ===========================================
            if (terminal) {
                System.out.println("🏁 TERMINAL STATE REACHED");
                break;
            }

            if (plan != null && plan.completed) {
                System.out.println("🏁 PLAN COMPLETED");
                break;
            }

            if (ProgressEngine.isStuck(stagnation)) {
                System.out.println("⚠️ STUCK → forcing replan");
                PlanningEngine.build(GoalEngine.getCurrentGoal());
                stagnation = 0;
            }

            // 🔄 decay memory over time
            SessionState.decay();
            BehaviorStore.decay();
            BehaviorGraph.decayAll();
            LearningEngine.decay();
        }

        // ===========================================
        // 📤 EXPORT SCENARIO
        // ===========================================
        ScenarioTracker.printSmart();
        ScenarioExporter.exportFull();

        System.out.println("✅ DONE");
    }

    // ===================================================
    private static boolean execute(String action, WebDriver driver) {

        if (action == null) return false;

        try {

            switch (action) {

                case "ClickAction":
                    new ClickAction().execute(driver);
                    return true;

                case "FormAction":
                    new FormAction().execute(driver);
                    return true;

                case "NavigationAction":
                    new NavigationAction().execute(driver);
                    return true;

                default:
                    return false;
            }

        } catch (Exception e) {

            System.out.println("❌ Failed: " + action + " | " + e.getMessage());
            return false;
        }
    }

    // ===================================================
    private static State buildState(WebDriver driver) {

        int domSize = driver.findElements(By.xpath("//*")).size();

        boolean hasForm = !driver.findElements(By.tagName("form")).isEmpty();
        boolean hasLinks = !driver.findElements(By.tagName("a")).isEmpty();

        return new State(
                driver.getCurrentUrl(),
                domSize,
                safeGoal(),
                hasForm,
                hasLinks
        );
    }

    // ===================================================
    private static String safeGoal() {

        return GoalEngine.getCurrentGoal() != null
                ? GoalEngine.getCurrentGoal().name
                : "UNKNOWN";
    }
}