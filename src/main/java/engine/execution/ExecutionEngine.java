package engine.execution;

import ai.decision.DecisionEngine;
import ai.goal.GoalEngine;
import ai.intelligence.*;
import ai.learning.*;
import ai.planning.Plan;
import ai.planning.PlanningEngine;
import engine.actions.ClickAction;
import engine.actions.FormAction;
import engine.actions.NavigationAction;
import engine.state.SessionState;
import org.openqa.selenium.WebDriver;

public class ExecutionEngine {

    private static final int MAX_STEPS = 60;

    public static void run(WebDriver driver) {

        System.out.println("🚀 AI Engine V6 START");

        // ===========================================
        // 🧠 RESET SYSTEM
        // ===========================================
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
            State prevState =
                    StateFactory.build(driver);

            SessionState.markStateVisited(prevState);

            // ===========================================
            // 🎯 GOAL
            // ===========================================
            GoalEngine.update(driver);

            // ===========================================
            // 🧠 PLAN
            // ===========================================
            Plan plan =
                    PlanningEngine.getCurrentPlan();

            if (plan == null
                    || plan.isCompleted()
                    || plan.isFailed()) {

                plan =
                        PlanningEngine.build(driver);
            }

            String currentStep =
                    (plan != null)
                            ? plan.getCurrentStep()
                            : "NONE";

            System.out.println(
                    "📌 Goal → "
                            + safeGoal()
            );

            System.out.println(
                    "📌 Plan Step → "
                            + currentStep
            );

            // ===========================================
            // 🧠 DECISION (PLAN FIRST)
            // ===========================================
            String action;

            String plannedAction =
                    (plan != null)
                            ? plan.getCurrentStep()
                            : null;

            // planner = primary source
            if (plannedAction != null) {

                action = plannedAction;

                System.out.println(
                        "🧠 Using Plan → "
                                + action
                );
            }

            // fallback to AI decision
            else {

                action =
                        DecisionEngine.decide(
                                driver,
                                prevState
                        );

                System.out.println(
                        "🧠 Fallback Decision → "
                                + action
                );
            }

            System.out.println(
                    "🤖 Action → "
                            + action
            );

            // ===========================================
            // ⚙️ EXECUTION
            // ===========================================
            boolean success =
                    execute(action, driver);

            if (!success) {

                SessionState.recordFailure(
                        action,
                        prevState
                );
            }

            // ===========================================
            // 🧠 STATE (AFTER)
            // ===========================================
            State nextState =
                    StateFactory.build(driver);

            SessionState.markStateVisited(nextState);

            // ===========================================
            // 🔁 LOOP DETECTION
            // ===========================================
            LoopDetector.record(nextState);

            boolean revisited =
                    LoopDetector.isLooping(nextState);

            // ===========================================
            // 📈 PROGRESS
            // ===========================================
            double progress =
                    ProgressEngine.evaluateProgress(
                            driver,
                            prevState,
                            nextState
                    );

            boolean terminal =
                    ProgressEngine.isTerminal(
                            driver,
                            nextState
                    );

            if (progress > 0) {
                stagnation = 0;
            } else {
                stagnation++;
            }

            // ===========================================
            // 🎁 REWARD
            // ===========================================
            double reward =
                    RewardEngine.calculate(
                            prevState,
                            nextState,
                            action,
                            success,
                            revisited,
                            progress,
                            terminal
                    );

            SessionState.reward(
                    nextState,
                    reward
            );

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

            BehaviorStore.record(
                    prevState,
                    action,
                    success,
                    reward
            );

            BehaviorGraph.record(
                    prevState,
                    action,
                    nextState,
                    driver.getCurrentUrl(),
                    success,
                    reward
            );

            // ===========================================
            // 📚 SCENARIO TRACKING
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
            PlanningEngine.update(
                    success,
                    driver
            );

            // ===========================================
            // 🛑 EXIT CONDITIONS
            // ===========================================
            if (terminal) {

                System.out.println(
                        "🏁 TERMINAL STATE REACHED"
                );

                break;
            }

            if (plan != null
                    && plan.isCompleted()) {

                System.out.println(
                        "🏁 PLAN COMPLETED"
                );

                break;
            }

            if (ProgressEngine.isStuck(stagnation)) {

                System.out.println(
                        "⚠️ STUCK → forcing replan"
                );

                PlanningEngine.build(driver);

                stagnation = 0;
            }

            // ===========================================
            // 🔄 MEMORY DECAY
            // ===========================================
            SessionState.decay();
            BehaviorStore.decay();
            BehaviorGraph.decayAll();
            LearningEngine.decay();
        }

        // ===========================================
        // 📤 EXPORT
        // ===========================================
        ScenarioTracker.printSmart();
        ScenarioExporter.exportFull();

        System.out.println("✅ DONE");
    }

    // ===================================================
    // ⚙️ ACTION EXECUTOR
    // ===================================================
    private static boolean execute(
            String action,
            WebDriver driver
    ) {

        if (action == null) {
            return false;
        }

        try {

            switch (action) {

                case "ClickAction":
                    new ClickAction()
                            .execute(driver);
                    return true;

                case "FormAction":
                    new FormAction()
                            .execute(driver);
                    return true;

                case "NavigationAction":
                    new NavigationAction()
                            .execute(driver);
                    return true;

                default:

                    System.out.println(
                            "❌ Unknown action: "
                                    + action
                    );

                    return false;
            }

        } catch (Exception e) {

            System.out.println(
                    "❌ Failed: "
                            + action
                            + " | "
                            + e.getMessage()
            );

            return false;
        }
    }

    // ===================================================
    // 🎯 SAFE GOAL
    // ===================================================
    private static String safeGoal() {

        return GoalEngine.getCurrentGoal() != null
                ? GoalEngine.getCurrentGoal().name
                : "UNKNOWN";
    }
}