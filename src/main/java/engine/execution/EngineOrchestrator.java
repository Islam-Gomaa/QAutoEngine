package engine.execution;

import ai.decision.DecisionEngine;
import ai.goal.GoalEngine;
import ai.planning.PlanningEngine;
import ai.planning.Plan;

import ai.learning.*;
import ai.intelligence.*;

import engine.actions.*;

import org.openqa.selenium.WebDriver;

public class EngineOrchestrator {

    private static final int MAX_STEPS = 60;

    public void run(WebDriver driver) {

        System.out.println("🚀 AI Orchestrator V6 START");

        GoalEngine.init();
        PlanningEngine.reset();
        ScenarioTracker.reset();
        LoopDetector.reset();

        int step = 0;
        int stagnation = 0;

        while (step++ < MAX_STEPS) {

            log("\n====================");
            log("STEP " + step);
            log("====================");

            // ===================================
            // 🧠 STATE
            // ===================================
            State prevState = StateBuilder.from(driver);

            // ===================================
            // 🧠 GOAL
            // ===================================
            GoalEngine.update(driver);

            // ===================================
            // 🧠 PLAN
            // ===================================
            Plan plan = PlanningEngine.getCurrentPlan();

            if (plan == null || plan.completed || plan.failed) {
                plan = PlanningEngine.build(GoalEngine.getCurrentGoal());
            }

            log("🎯 Goal → " + safeGoal());
            log("📌 Plan → " + (plan != null ? plan.getCurrentStep() : "NONE"));

            // ===================================
            // 🧠 DECISION
            // ===================================
            String actionName = DecisionEngine.decide(driver, prevState);

            log("🤖 Action → " + actionName);

            // ===================================
            // ⚙️ EXECUTION (V6)
            // ===================================
            ActionExecutor.Result result =
                    executeAction(driver, actionName, prevState);

            boolean success = result.success;

            // ===================================
            // 🧠 NEXT STATE
            // ===================================
            State nextState = StateBuilder.from(driver);

            // ===================================
            // 🔁 LOOP
            // ===================================
            LoopDetector.record(nextState);
            boolean revisited = LoopDetector.isLooping(nextState);

            // ===================================
            // 📈 PROGRESS
            // ===================================
            double progress = ProgressEngine.evaluateProgress(
                    driver,
                    prevState,
                    nextState,
                    prevState.url,
                    nextState.url,
                    prevState.domSize,
                    nextState.domSize
            );

            boolean terminal =
                    ProgressEngine.isTerminal(driver, nextState);

            if (progress < 1) stagnation++;
            else stagnation = 0;

            // ===================================
            // 🧠 REWARD
            // ===================================
            double reward = RewardEngine.calculate(
                    prevState,
                    nextState,
                    actionName,
                    success,
                    revisited,
                    progress,
                    terminal
            );

            // ===================================
            // 🧠 LEARNING
            // ===================================
            LearningEngine.learn(
                    prevState,
                    nextState,
                    actionName,
                    nextState.url,
                    success,
                    reward,
                    revisited
            );

            BehaviorStore.record(prevState, actionName, success, reward);
            BehaviorGraph.record(prevState, actionName, nextState,
                    nextState.url, success, reward);

            // ===================================
            // 📊 SCENARIO
            // ===================================
            ScenarioTracker.record(
                    actionName,
                    nextState.url,
                    nextState,
                    progress
            );

            // ===================================
            // 🧠 PLAN UPDATE
            // ===================================
            PlanningEngine.update(success);

            // ===================================
            // 🛑 EXIT
            // ===================================
            if (terminal) {
                log("🏁 TERMINAL");
                break;
            }

            if (plan != null && plan.completed) {
                log("🏁 PLAN DONE");
                break;
            }

            if (ProgressEngine.isStuck(stagnation)) {
                log("⚠️ STUCK → Replan");
                PlanningEngine.build(GoalEngine.getCurrentGoal());
                stagnation = 0;
            }
        }

        ScenarioTracker.printSmart();
        ScenarioExporter.exportFull();

        log("✅ AI DONE");
    }

    // ===================================================
    private ActionExecutor.Result executeAction(WebDriver driver,
                                                String actionName,
                                                State state) {

        return ActionExecutor.execute(driver, () -> {

            switch (actionName) {

                case "ClickAction":
                    new ClickAction().execute(driver);
                    break;

                case "FormAction":
                    new FormAction().execute(driver);
                    break;

                case "NavigationAction":
                    new NavigationAction().execute(driver);
                    break;
            }

        }, actionName, state);
    }

    private String safeGoal() {
        return GoalEngine.getCurrentGoal() != null
                ? GoalEngine.getCurrentGoal().name
                : "UNKNOWN";
    }

    private void log(String msg) {
        System.out.println(msg);
    }
}