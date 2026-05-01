package engine.learning;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BehaviorGraph {

    // state → action → (to → stats)
    private static final Map<String,
            Map<String,
                    Map<String, TransitionStats>>> graph =
            new ConcurrentHashMap<>();

    // Q-table (state|action → value)
    private static final Map<String, Double> qTable =
            new ConcurrentHashMap<>();

    // state → best Q (optimization 🔥)
    private static final Map<String, Double> maxQCache =
            new ConcurrentHashMap<>();

    // ================= PARAMETERS =================
    private static final double LR = 0.3;
    private static final double DISCOUNT = 0.85;

    // 🔥 dynamic exploration
    private static double explorationRate = 0.12;

    // 🔥 decay factor
    private static final double DECAY = 0.97;

    // ================= STATS =================
    private static class TransitionStats {
        double count;
        double success;
        double failure;
        long lastSeen;

        void record(boolean ok) {
            count++;
            if (ok) success++; else failure++;
            lastSeen = System.currentTimeMillis();
        }

        double score() {

            if (count == 0) return 0;

            double ratio = success / count;

            // 🔥 recency
            double age = (System.currentTimeMillis() - lastSeen) / 10000.0;
            double recency = 1.0 / (1.0 + age);

            // 🔥 confidence
            double confidence = Math.min(1.0, count / 10.0);

            return (ratio * confidence) + (recency * 0.3);
        }
    }

    // ===================================================
    // 🔗 RECORD
    // ===================================================
    public static void record(String state,
                              String action,
                              String nextState,
                              String toUrl,
                              boolean success,
                              double reward) {

        graph
                .computeIfAbsent(state, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(action, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(toUrl, k -> new TransitionStats())
                .record(success);

        updateQ(state, action, nextState, reward);
    }

    // ===================================================
    // 🧠 Q-UPDATE (IMPROVED)
    // ===================================================
    private static void updateQ(String state,
                                String action,
                                String nextState,
                                double reward) {

        String key = state + "|" + action;

        double current = qTable.getOrDefault(key, 0.0);
        double maxNext = maxQCache.getOrDefault(nextState, 0.0);

        double newQ = current + LR *
                (reward + DISCOUNT * maxNext - current);

        qTable.put(key, newQ);

        maxQCache.merge(state, newQ, Math::max);
    }

    // ===================================================
    // 🔥 DYNAMIC EXPLORATION
    // ===================================================
    public static void adjustExploration(boolean success) {

        if (success) {
            explorationRate *= 0.95;
        } else {
            explorationRate *= 1.05;
        }

        explorationRate = Math.max(0.05, Math.min(0.3, explorationRate));
    }

    // ===================================================
    // 🧠 HYBRID SCORE
    // ===================================================
    private static double getHybridScore(String state, String action) {

        String key = state + "|" + action;

        double q = qTable.getOrDefault(key, 0.0);

        double graphScore = graph
                .getOrDefault(state, Map.of())
                .getOrDefault(action, Map.of())
                .values().stream()
                .mapToDouble(TransitionStats::score)
                .sum();

        // 🔥 normalization
        double normalizedGraph = Math.tanh(graphScore);

        double exploration = (Math.random() < explorationRate)
                ? Math.random()
                : 0;

        return q + normalizedGraph + exploration;
    }

    // ===================================================
    // 🎯 ACTION SUGGESTION
    // ===================================================
    public static Optional<String> suggestAction(String state) {

        Map<String, Map<String, TransitionStats>> actions = graph.get(state);

        if (actions == null || actions.isEmpty())
            return Optional.empty();

        return actions.keySet().stream()
                .max(Comparator.comparingDouble(a ->
                        getHybridScore(state, a)));
    }

    // ===================================================
    // 🌍 NEXT URL
    // ===================================================
    public static Optional<String> suggestNextUrl(String state,
                                                  String action) {

        Map<String, Map<String, TransitionStats>> actions = graph.get(state);

        if (actions == null) return Optional.empty();

        Map<String, TransitionStats> targets = actions.get(action);

        if (targets == null || targets.isEmpty())
            return Optional.empty();

        return targets.entrySet().stream()
                .max(Comparator.comparingDouble(e ->
                        e.getValue().score()))
                .map(Map.Entry::getKey);
    }

    // ===================================================
    // 🔁 DECAY GRAPH
    // ===================================================
    private static void decayGraph() {

        for (var stateEntry : graph.values()) {

            for (var actionEntry : stateEntry.values()) {

                for (TransitionStats stats : actionEntry.values()) {

                    stats.count *= 0.95;
                    stats.success *= 0.95;
                    stats.failure *= 0.95;
                }
            }
        }
    }

    // ===================================================
    // 🔁 FULL DECAY
    // ===================================================
    public static void decayAll() {

        for (String key : qTable.keySet()) {
            qTable.put(key, qTable.get(key) * DECAY);
        }

        decayGraph();
    }

    // ===================================================
    // 🔥 LOOP PENALTY
    // ===================================================
    public static double applyLoopPenalty(String state, double score) {

        if (LoopDetector.isLooping(state)) {
            return score - 3;
        }

        return score;
    }

    // ===================================================
    // 🧠 ACTION SCORE (FOR ENGINE)
    // ===================================================
    public static double getActionScore(String state, String action) {

        String key = state + "|" + action;

        double q = qTable.getOrDefault(key, 0.0);

        double graphScore = graph
                .getOrDefault(state, Map.of())
                .getOrDefault(action, Map.of())
                .values().stream()
                .mapToDouble(TransitionStats::score)
                .sum();

        return q + Math.tanh(graphScore);
    }

    // ===================================================
    // 🧠 GET Q-TABLE
    // ===================================================
    public static Map<String, Double> getQTable() {
        return qTable;
    }

    // ===================================================
    // 🧹 RESET
    // ===================================================
    public static void reset() {
        graph.clear();
        qTable.clear();
        maxQCache.clear();
        explorationRate = 0.12;
    }
}