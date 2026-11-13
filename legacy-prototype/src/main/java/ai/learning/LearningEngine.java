package ai.learning;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LearningEngine {

    // ===================================================
    // 🧠 TRANSITION MEMORY
    // prevState|action|nextState
    // ===================================================
    private static final Map<String, LearningStats> memory =
            new ConcurrentHashMap<>();

    // ===================================================
    // 📊 MODEL
    // ===================================================
    private static class LearningStats {

        double rewardSum;
        int success;
        int failure;
        int count;

        long lastUpdated;

        synchronized void update(boolean ok,
                                 double reward) {

            // 🔥 soft decay
            rewardSum *= 0.99;

            rewardSum += reward;
            count++;

            if (ok) success++;
            else failure++;

            lastUpdated = System.currentTimeMillis();
        }

        synchronized double score() {

            if (count == 0) {
                return 0.4; // exploration bias
            }

            // ===============================
            // success ratio
            // ===============================
            double successRate =
                    (success + 1.0) /
                            (success + failure + 2.0);

            // ===============================
            // reward quality
            // ===============================
            double avgReward =
                    rewardSum / Math.max(1, count);

            // ===============================
            // recency boost
            // ===============================
            double age =
                    (System.currentTimeMillis()
                            - lastUpdated) / 10000.0;

            double recency =
                    1.0 / (1.0 + age);

            recency = clamp(recency, 0.2, 1.0);

            // ===============================
            // confidence
            // ===============================
            double confidence =
                    Math.min(1.0, count / 20.0);

            // ===============================
            // final score
            // ===============================
            double score =
                    (successRate * 0.40)
                            + (avgReward * 0.30)
                            + (recency * 0.15)
                            + (confidence * 0.15);

            return clamp(score, -5, 10);
        }

        synchronized void decay() {
            rewardSum *= 0.97;
        }
    }

    // ===================================================
    // 🔥 MAIN LEARN
    // ===================================================
    public static void learn(State prev,
                             State next,
                             String action,
                             boolean success,
                             double reward) {

        if (prev == null
                || next == null
                || action == null) {
            return;
        }

        String key =
                buildKey(prev, action, next);

        memory
                .computeIfAbsent(
                        key,
                        k -> new LearningStats()
                )
                .update(success, reward);
    }

    // ===================================================
    // 🧠 SCORE
    // used by DecisionEngine
    // ===================================================
    public static double getScore(State state,
                                  String action) {

        if (state == null
                || action == null) {
            return 0.4;
        }

        double best = 0.4;

        String prefix =
                state.signature()
                        + "|" + action + "|";

        for (Map.Entry<String, LearningStats> e
                : memory.entrySet()) {

            if (e.getKey().startsWith(prefix)) {

                best = Math.max(
                        best,
                        e.getValue().score()
                );
            }
        }

        return best;
    }

    // ===================================================
    // 🎯 BEST ACTION
    // ===================================================
    public static String getBestAction(State state) {

        if (state == null) {
            return null;
        }

        String[] actions = {
                "ClickAction",
                "FormAction",
                "NavigationAction"
        };

        String bestAction = null;
        double bestScore = -999;

        for (String action : actions) {

            double score =
                    getScore(state, action);

            if (score > bestScore) {

                bestScore = score;
                bestAction = action;
            }
        }

        return bestAction;
    }

    // ===================================================
    // 🔄 DECAY
    // ===================================================
    public static void decay() {

        for (LearningStats s
                : memory.values()) {

            s.decay();
        }
    }

    // ===================================================
    // 🧹 CLEANUP
    // ===================================================
    public static void cleanup() {

        long now =
                System.currentTimeMillis();

        memory.entrySet().removeIf(e -> {

            LearningStats s =
                    e.getValue();

            return s.count < 2
                    && (now - s.lastUpdated)
                    > 120000;
        });
    }

    // ===================================================
    // 📊 DEBUG
    // ===================================================
    public static void printStats() {

        System.out.println(
                "\n===== 🧠 LEARNING =====");

        memory.forEach((k, v) -> {

            System.out.println(
                    k
                            + " => "
                            + v.score());
        });
    }

    // ===================================================
    // 🧹 RESET
    // ===================================================
    public static void reset() {
        memory.clear();
    }

    // ===================================================
    // 🔧 HELPERS
    // ===================================================
    private static String buildKey(State prev,
                                   String action,
                                   State next) {

        return prev.signature()
                + "|"
                + action
                + "|"
                + next.signature();
    }

    private static double clamp(double v,
                                double min,
                                double max) {

        return Math.max(
                min,
                Math.min(max, v)
        );
    }
}