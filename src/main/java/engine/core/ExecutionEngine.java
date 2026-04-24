package engine.core;

import engine.actions.Action;
import engine.context.ContextEngine;
import engine.decider.ActionDecider;
import engine.distribution.DistributionManager;
import engine.goal.GoalEngine;
import engine.learning.BehaviorGraph;
import engine.learning.BehaviorStore;
import engine.learning.LearningEngine;
import engine.state.SessionState;
import org.openqa.selenium.WebDriver;

import java.util.List;

public class ExecutionEngine {

    private final List<Action> actions;

    // 🔥 limits
    private final int maxSteps = 30;

    public ExecutionEngine(List<Action> actions) {
        this.actions = actions;
    }

    // ===================================================
    // 🚀 AI LOOP
    // ===================================================
    public void run(WebDriver driver) {

        System.out.println("🧠 Execution Engine START");

        int step = 0;

        while (step < maxSteps) {

            try {

                System.out.println("\n🔁 Step: " + (step + 1));

                // ===================================================
                // 🔍 OBSERVE
                // ===================================================
                String currentUrl = driver.getCurrentUrl();

                if (SessionState.isUrlVisited(currentUrl)) {

                    System.out.println("⚠️ Already visited");

                    // 🔥 لو السيستم علق → وقف
                    if (SessionState.isStuck()) {
                        System.out.println("🚫 Stuck detected, stopping...");
                        break;
                    }

                    // 🔥 غير كده كمل exploration
                    System.out.println("🔄 Continuing exploration...");

                } else {
                    // URL جديد → سجله
                    SessionState.markUrlVisited(currentUrl);
                }

                // ===================================================
                // 🧠 CONTEXT + GOAL
                // ===================================================
                ContextEngine.ContextType context = ContextEngine.detect(driver);

                System.out.println("🧠 Context: " + context);

                GoalEngine.updateProgress(context);

                if (GoalEngine.isGoalReached(context)) {
                    System.out.println("🎯 Goal reached → " + GoalEngine.getGoal());
                    break;
                }

                System.out.println("🎯 Current Goal: " + GoalEngine.getGoal());

                // ===================================================
                // 🧠 DECIDE
                // ===================================================
                Action nextAction = decideNextAction();

                if (nextAction == null) {
                    System.out.println("🛑 No more actions to execute");
                    break;
                }

                String actionName = nextAction.getClass().getSimpleName();

                System.out.println("🧠 Decided Action: " + actionName);

                // ===================================================
                // ⚙️ ACT
                // ===================================================
                String beforeUrl = driver.getCurrentUrl();

                boolean success = executeWithRetry(driver, nextAction);

                // ===================================================
                // 🔍 VALIDATE
                // ===================================================
                String afterUrl = driver.getCurrentUrl();
                System.out.println("🔎 Current URL: " + afterUrl);

                // ===================================================
                // 🧠 BEHAVIOR GRAPH
                // ===================================================
                BehaviorGraph.record(beforeUrl, actionName, afterUrl);

                if (success) {
                    BehaviorStore.recordSuccess(actionName);
                } else {
                    BehaviorStore.recordFailure(actionName);
                }

                // ===================================================
                // 📈 LEARNING
                // ===================================================
                LearningEngine.learn();
                LearningEngine.learnPath(afterUrl, success);
                LearningEngine.decay();

                // ===================================================
                // 🌐 DISTRIBUTION
                // ===================================================
                if (DistributionManager.hasNext()) {

                    String nextUrl = DistributionManager.next();

                    if (nextUrl != null && !SessionState.isUrlVisited(nextUrl)) {

                        System.out.println("🌍 Navigating to next path: " + nextUrl);

                        driver.navigate().to(nextUrl);
                    }
                }

                step++;

            } catch (Exception e) {

                System.out.println("❌ Engine crashed at step " + step);
                recover(driver);
            }
        }

        System.out.println("✅ Execution Engine END");
    }

    // ===================================================
    // 🧠 DECISION
    // ===================================================
    private Action decideNextAction() {

        for (Action action : actions) {

            if (ActionDecider.shouldExecute(action)) {
                return action;
            }
        }

        // fallback
        if (!actions.isEmpty()) {
            System.out.println("⚠️ Fallback → executing first action");
            return actions.get(0);
        }

        return null;
    }

    // ===================================================
    // 🔁 RETRY + RESULT
    // ===================================================
    private boolean executeWithRetry(WebDriver driver, Action action) {

        int retries = 2;
        String actionName = action.getClass().getSimpleName();

        SessionState.recordAction(actionName);

        while (retries-- > 0) {

            try {

                System.out.println("⚙️ Running: " + actionName);

                action.execute(driver);

                return true;

            } catch (Exception e) {

                System.out.println("❌ Failed, retrying...");

                SessionState.recordFailure(actionName);

                recover(driver);
            }
        }

        System.out.println("🚫 Skipped: " + actionName);

        return false;
    }

    // ===================================================
    // 🧯 RECOVERY
    // ===================================================
    private void recover(WebDriver driver) {

        try {
            driver.navigate().back();
        } catch (Exception ignored) {}
    }
}