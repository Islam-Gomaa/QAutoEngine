package engine.core;

import ai.learning.BehaviorGraph;
import ai.learning.BehaviorStore;
import ai.learning.LearningEngine;
import ai.learning.RewardEngine;
import engine.actions.Action;
import ai.decision.DecisionEngine;
import ai.intelligence.ProgressEngine;
import ai.intelligence.ScenarioTracker;
import ai.state.SessionState;
import org.openqa.selenium.*;

import java.util.List;

public class ExecutionEngine {

    private final List<Action> actions;
    private final int maxSteps = 50;

    public ExecutionEngine(List<Action> actions) {
        this.actions = actions;
    }

    public void run(WebDriver driver) {

        System.out.println("🧠 AI Execution Engine START");

        ScenarioTracker.reset();

        int step = 0;
        int stepsWithoutProgress = 0;

        while (step < maxSteps) {

            try {

                System.out.println("\n🔁 Step: " + (step + 1));

                // ================= OBSERVE =================
                String beforeUrl = driver.getCurrentUrl();
                int beforeDom = driver.findElements(By.xpath("//*")).size();

                LearningEngine.State prevState =
                        LearningEngine.buildState(null, beforeUrl, null, beforeDom);

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

                sleep(500);

                // ================= AFTER =================
                String afterUrl = driver.getCurrentUrl();
                int afterDom = driver.findElements(By.xpath("//*")).size();

                LearningEngine.State nextState =
                        LearningEngine.buildState(null, afterUrl, null, afterDom);

                // ================= 🔥 PROGRESS =================
                double progressScore = ProgressEngine.evaluateProgress(
                        driver,
                        beforeUrl,
                        afterUrl,
                        beforeDom,
                        afterDom
                );

                boolean progress = progressScore > 2;

                if (progress) stepsWithoutProgress = 0;
                else stepsWithoutProgress++;

                boolean success = executed && progress;
                boolean revisited = SessionState.isUrlVisited(afterUrl);

                boolean terminal = ProgressEngine.isTerminal(driver);

                // ================= 💰 REWARD =================
                double reward = RewardEngine.calculate(
                        prevState,
                        nextState,
                        actionName,
                        success,
                        revisited,
                        progressScore,
                        terminal
                );

                // 🔁 loop penalty
                if (ScenarioTracker.isLooping(afterUrl)) {
                    reward -= 5;
                    System.out.println("🔁 Loop penalty applied");
                }

                System.out.println("💰 Reward: " + reward);

                // ================= 🧠 LEARNING =================
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

                // ================= 📊 SCENARIO =================
                ScenarioTracker.record(actionName, afterUrl, progressScore);

                // ================= 🎯 TERMINAL =================
                if (terminal) {
                    System.out.println("🎯 SCENARIO COMPLETED!");
                    ScenarioTracker.printSmart();
                    break;
                }

                // ================= 💣 STUCK HANDLING =================
                if (ProgressEngine.isStuck(stepsWithoutProgress)) {
                    System.out.println("⚠️ Stuck detected → navigating back");
                    driver.navigate().back();
                    stepsWithoutProgress = 0;
                }

                // ================= VISITED =================
                if (!revisited) {
                    SessionState.markUrlVisited(afterUrl);
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

        // 🧠 memory
        score += BehaviorStore.getScore(state.toString(), name) * 10;

        // 🧠 graph
        score += BehaviorGraph.getActionScore(state.toString(), name) * 5;

        // 🧠 rules
        score += DecisionEngine.scoreAction(name, driver, state);

        // 🔥 exploration
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