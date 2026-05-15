package ai.planning;

import ai.goal.GoalEngine;
import ai.goal.GoalEngine.Goal;
import ai.learning.State;
import ai.learning.LoopDetector;
import engine.state.SessionState;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import java.util.*;

public class MCTSPlanner {

    private static final List<String> ACTIONS = List.of(
            "ClickAction",
            "FormAction",
            "NavigationAction"
    );

    private static final int SIMULATIONS = 40;
    private static final int ROLLOUT_DEPTH = 3;

    // ===================================================
    public static String plan(WebDriver driver) {

        Goal goal = GoalEngine.getCurrentGoal();

        State rootState = buildState(driver);

        MCTSNode root = new MCTSNode(null, rootState, null);

        for (int i = 0; i < SIMULATIONS; i++) {

            // 🔍 selection
            MCTSNode node = select(root);

            // 🌱 expansion
            if (node.isLeaf()) {
                expand(node);
            }

            // 🎯 choose child (not random blindly)
            MCTSNode toSimulate = selectOrExplore(node);

            // 🎯 rollout
            double reward = rollout(toSimulate, goal);

            // 🔄 backprop
            node.update(reward);
        }

        return bestAction(root);
    }

    // ===================================================
    private static MCTSNode select(MCTSNode node) {

        while (!node.isLeaf()) {

            node = node.children.stream()
                    .max(Comparator.comparingDouble(MCTSNode::getScore))
                    .orElse(node);
        }

        return node;
    }

    // ===================================================
    private static void expand(MCTSNode node) {

        if (!node.children.isEmpty()) return;

        for (String action : ACTIONS) {

            node.addChild(new MCTSNode(
                    action,
                    node.state,
                    node
            ));
        }
    }

    // ===================================================
    // 💣 smarter exploration
    // ===================================================
    private static MCTSNode selectOrExplore(MCTSNode node) {

        if (node.children.isEmpty()) return node;

        return node.children.stream()
                .min(Comparator.comparingInt(n -> n.visits))
                .orElse(node.children.get(0));
    }

    // ===================================================
    private static double rollout(MCTSNode node, Goal goal) {

        double total = 0;
        State current = node.state;

        for (int depth = 0; depth < ROLLOUT_DEPTH; depth++) {

            String action = guidedAction(current, goal);

            double reward = evaluate(action, current, goal);

            total += reward;

            current = nextState(current, action);
        }

        return total;
    }

    // ===================================================
    // 💣 smarter than random
    // ===================================================
    private static String guidedAction(State state, Goal goal) {

        return ACTIONS.stream()
                .max(Comparator.comparingDouble(a ->
                        heuristic(a, goal)
                                + SessionState.getReward(state)
                                - (SessionState.shouldAvoidState(state) ? 10 : 0)
                ))
                .orElse("NavigationAction");
    }

    // ===================================================
    private static double evaluate(String action, State state, Goal goal) {

        double score = 0;

        score += heuristic(action, goal);

        // loop penalty
        score += LoopDetector.loopPenalty(state);

        // learning memory
        score += SessionState.getReward(state);

        // avoid bad states
        if (SessionState.shouldAvoidState(state)) {
            score -= 15;
        }

        return score;
    }

    // ===================================================
    private static State nextState(State current, String action) {

        int newDom = current.domSize + new Random().nextInt(20) - 10;

        return new State(
                current.url,
                Math.max(1, newDom),
                current.goal,
                current.hasForm,
                current.hasLinks
        );
    }

    // ===================================================
    private static double heuristic(String action, Goal goal) {

        double score = 0;

        if ("AUTH".equals(goal.name) && action.equals("FormAction"))
            score += 20;

        if ("FORM".equals(goal.name) && action.equals("FormAction"))
            score += 10;

        if ("NAVIGATION".equals(goal.name) && action.equals("NavigationAction"))
            score += 15;

        return score;
    }

    // ===================================================
    private static String bestAction(MCTSNode root) {

        return root.children.stream()
                .max(Comparator.comparingDouble(
                        n -> n.getAverageReward() * Math.log(n.visits + 1)
                ))
                .map(n -> n.action)
                .orElse("NavigationAction");
    }

    // ===================================================
    private static State buildState(WebDriver driver) {

        int domSize = driver.findElements(By.xpath("//*")).size();

        boolean hasForm = !driver.findElements(By.tagName("form")).isEmpty();
        boolean hasLinks = !driver.findElements(By.tagName("a")).isEmpty();

        return new State(
                driver.getCurrentUrl(),
                domSize,
                "PLANNING",
                hasForm,
                hasLinks
        );
    }
}