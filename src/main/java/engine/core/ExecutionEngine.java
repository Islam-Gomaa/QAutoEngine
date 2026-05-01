package engine.core;

import engine.actions.Action;
import engine.context.ContextEngine;
import engine.decision.DecisionEngine;
import engine.goal.GoalEngine;
import engine.learning.*;
import engine.state.SessionState;
import org.openqa.selenium.*;

import java.util.List;

public class ExecutionEngine {

    private final List<Action> actions;
    private final int maxSteps = 40;

    public ExecutionEngine(List<Action> actions) {
        this.actions = actions;
    }

    public void run(WebDriver driver) {

        System.out.println("🧠 AI Execution Engine START");

        int step = 0;

        while (step < maxSteps) {

            try {

                System.out.println("\n🔁 Step: " + (step + 1));

                // ================= OBSERVE =================
                String beforeUrl = driver.getCurrentUrl();

                ContextEngine.ContextType context = ContextEngine.detect(driver);
                GoalEngine.update(context);
                GoalEngine.GoalType goal = GoalEngine.getGoal();

                int beforeDom = driver.findElements(By.xpath("//*")).size();

                LearningEngine.State prevState =
                        LearningEngine.buildState(context, beforeUrl, goal, beforeDom);

                LoopDetector.record(prevState.toString());

                System.out.println("🧠 State: " + prevState);

                // ================= DECIDE =================
                Action bestAction = selectBestAction(driver, prevState);

                if (bestAction == null) {
                    System.out.println("🛑 No action selected");
                    break;
                }

                String actionName = bestAction.getClass().getSimpleName();
                System.out.println("🎯 Selected: " + actionName);

                // ================= EXECUTE =================
                boolean executed = executeWithRetry(driver, bestAction);

                sleep(700);

                // ================= AFTER =================
                String afterUrl = driver.getCurrentUrl();
                int afterDom = driver.findElements(By.xpath("//*")).size();

                LearningEngine.State nextState =
                        LearningEngine.buildState(context, afterUrl, goal, afterDom);

                boolean changed = !beforeUrl.equals(afterUrl)
                        || beforeDom != afterDom;

                boolean success = executed && changed;

                boolean revisited = SessionState.isUrlVisited(afterUrl);

                // ================= REWARD =================
                double reward = RewardEngine.calculate(
                        prevState,
                        nextState,
                        actionName,
                        success,
                        revisited
                );

                if (LoopDetector.isLooping(prevState.toString())) {
                    reward -= 5;
                    System.out.println("🔁 Loop penalty applied");
                }

                // ================= LEARNING =================
                BehaviorGraph.record(
                        prevState.toString(),
                        actionName,
                        nextState.toString(),
                        afterUrl,
                        success,
                        reward
                );

                BehaviorStore.record(
                        prevState.toString(),
                        actionName,
                        success
                );

                BehaviorGraph.adjustExploration(success);

                System.out.println("🧠 Reward: " + reward);

                // ================= VISITED =================
                if (!revisited) {
                    SessionState.markUrlVisited(afterUrl);
                }

                // ================= SMART BREAK =================
                if (GoalEngine.isCompleted(GoalEngine.GoalType.COMPLETE_PAYMENT)) {
                    System.out.println("🏁 Goal Completed!");
                    break;
                }

                if (!changed) {
                    System.out.println("⚠️ No change → forcing exploration");
                }

                step++;

            } catch (Exception e) {

                System.out.println("❌ Crash at step " + step);
                recover(driver);
            }
        }

        System.out.println("✅ Execution Engine END");
    }

    // ===================================================
    private Action selectBestAction(WebDriver driver,
                                    LearningEngine.State state) {

        double bestScore = Double.NEGATIVE_INFINITY;
        Action best = null;

        for (Action action : actions) {

            double score = evaluateAction(driver, action, state);

            if (score > bestScore) {
                bestScore = score;
                best = action;
            }
        }

        return best;
    }

    private double evaluateAction(WebDriver driver,
                                  Action action,
                                  LearningEngine.State state) {

        String name = action.getClass().getSimpleName();

        double score = 0;

        // 🧠 Learning memory
        score += BehaviorStore.getScore(state.toString(), name) * 10;

        // 🧠 Graph knowledge
        score += BehaviorGraph.getActionScore(state.toString(), name) * 5;

        // 🧠 Decision rules
        score += DecisionEngine.scoreAction(name, driver, state);

        // 🔥 exploration (controlled)
        if (Math.random() < 0.15) {
            score += Math.random();
        }

        return score;
    }

    // ===================================================
    private boolean executeWithRetry(WebDriver driver, Action action) {

        int retries = 2;

        while (retries-- > 0) {
            try {
                action.execute(driver);
                return true;
            } catch (Exception e) {
                recover(driver);
            }
        }

        return false;
    }

    private void recover(WebDriver driver) {
        try { driver.navigate().back(); } catch (Exception ignored) {}
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (Exception ignored) {}
    }
}