package ai.planning;

import ai.learning.State;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MCTSNode {

    // ===============================
    // 🧠 CORE DATA
    // ===============================
    public String action;
    public State state;

    public MCTSNode parent;
    public List<MCTSNode> children;

    // ===============================
    // 📊 STATS
    // ===============================
    public int visits;
    public double totalReward;

    // ===============================
    // 🧠 META
    // ===============================
    public int depth;

    // ===============================
    // 🏗️ CONSTRUCTOR
    // ===============================
    public MCTSNode(String action, State state, MCTSNode parent) {

        this.action = action;
        this.state = state;
        this.parent = parent;
        this.children = new ArrayList<>();

        this.visits = 0;
        this.totalReward = 0;

        this.depth = (parent == null) ? 0 : parent.depth + 1;
    }

    // ===============================
    // 🌱 TREE HELPERS
    // ===============================
    public boolean isLeaf() {
        return children.isEmpty();
    }

    public void addChild(MCTSNode child) {

        // 💣 prevent duplicates
        for (MCTSNode c : children) {
            if (Objects.equals(c.action, child.action)
                    && Objects.equals(c.state, child.state)) {
                return;
            }
        }

        children.add(child);
    }

    // ===============================
    // 🔄 BACKPROP
    // ===============================
    public void update(double reward) {

        visits++;
        totalReward += reward;

        if (parent != null) {
            parent.update(reward);
        }
    }

    // ===============================
    // 🧠 UCB1 SCORE
    // ===============================
    public double getScore() {

        if (visits == 0) return Double.MAX_VALUE;

        double exploitation = totalReward / visits;

        double parentVisits = Math.max(1, parent != null ? parent.visits : 1);

        double exploration = Math.sqrt(
                Math.log(parentVisits + 1.0) / visits
        );

        double c = 1.41;

        return exploitation + c * exploration;
    }

    // ===============================
    // 📊 AVG REWARD
    // ===============================
    public double getAverageReward() {
        return visits == 0 ? 0 : totalReward / visits;
    }

    // ===============================
    // 🧠 BEST CHILD (no exploration)
    // ===============================
    public MCTSNode bestChild() {

        MCTSNode best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (MCTSNode child : children) {

            double score = child.getAverageReward();

            if (score > bestScore) {
                bestScore = score;
                best = child;
            }
        }

        return best;
    }

    // ===============================
    // 🔍 DEBUG
    // ===============================
    @Override
    public String toString() {
        return "MCTSNode{" +
                "action='" + action + '\'' +
                ", visits=" + visits +
                ", reward=" + totalReward +
                ", depth=" + depth +
                '}';
    }
}