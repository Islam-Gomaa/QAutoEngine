package ai.learning;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BehaviorGraph {

    private static final Map<String,
            Map<String,
                    Map<String, TransitionStats>>> graph =
            new ConcurrentHashMap<>();

    private static final Map<String, Double> qTable =
            new ConcurrentHashMap<>();

    // 🔥 remove stale cache issue
    // (we compute dynamically instead)
    private static final double LR = 0.25;
    private static final double DISCOUNT = 0.9;
    private static final double DECAY = 0.97;

    private static double explorationRate = 0.12;

    // ================= STATS =================
    private static class TransitionStats {
        double count;
        double success;
        double failure;
        long lastSeen;

        synchronized void record(boolean ok) {
            count++;
            if (ok) success++; else failure++;
            lastSeen = System.currentTimeMillis();
        }

        synchronized double score() {

            if (count == 0) return 0.5;

            double ratio = (success + 1) / (count + 2); // smoothing

            double age = (System.currentTimeMillis() - lastSeen) / 10000.0;
            double recency = 1.0 / (1.0 + age);

            double confidence = Math.min(1.0, count / 15.0);

            return (ratio * 0.6)
                    + (recency * 0.2)
                    + (confidence * 0.2);
        }
    }

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
    private static void updateQ(String state,
                                String action,
                                String nextState,
                                double reward) {

        String key = state + "|" + action;

        double current = qTable.getOrDefault(key, 0.0);
        double maxNext = getMaxQ(nextState);

        double target = reward + DISCOUNT * maxNext;

        double newQ = current + LR * (target - current);

        // 🔥 normalization (prevents explosion)
        newQ = Math.tanh(newQ);

        qTable.put(key, newQ);
    }

    // ===================================================
    private static double getMaxQ(String state) {

        return qTable.entrySet().stream()
                .filter(e -> e.getKey().startsWith(state + "|"))
                .mapToDouble(Map.Entry::getValue)
                .max()
                .orElse(0);
    }

    // ===================================================
    private static double getGraphScore(String state, String action) {

        Map<String, TransitionStats> targets =
                graph.getOrDefault(state, Map.of())
                        .getOrDefault(action, Map.of());

        if (targets.isEmpty()) return 0;

        // 🔥 average بدل sum
        return targets.values().stream()
                .mapToDouble(TransitionStats::score)
                .average()
                .orElse(0);
    }

    // ===================================================
    private static double getHybridScore(String state, String action) {

        String key = state + "|" + action;

        double q = qTable.getOrDefault(key, 0.0);
        double graphScore = getGraphScore(state, action);

        // 🔥 intelligent exploration
        double exploration = (Math.random() < explorationRate)
                ? (1 - Math.abs(q)) * Math.random()
                : 0;

        return q + graphScore + exploration;
    }

    // ===================================================
    public static Optional<String> suggestAction(String state) {

        Map<String, Map<String, TransitionStats>> actions = graph.get(state);

        if (actions == null || actions.isEmpty())
            return Optional.empty();

        return actions.keySet().stream()
                .max(Comparator.comparingDouble(a ->
                        applyLoopPenalty(state,
                                getHybridScore(state, a))));
    }

    // ===================================================
    public static Optional<String> suggestNextUrl(String state,
                                                  String action) {

        Map<String, TransitionStats> targets =
                graph.getOrDefault(state, Map.of())
                        .getOrDefault(action, Map.of());

        return targets.entrySet().stream()
                .max(Comparator.comparingDouble(e ->
                        e.getValue().score()))
                .map(Map.Entry::getKey);
    }

    // ===================================================
    public static void adjustExploration(boolean success) {

        if (success) explorationRate *= 0.97;
        else explorationRate *= 1.03;

        explorationRate = clamp(explorationRate, 0.05, 0.3);
    }

    // ===================================================
    public static void decayAll() {

        qTable.replaceAll((k, v) -> v * DECAY);

        for (var stateEntry : graph.values()) {
            for (var actionEntry : stateEntry.values()) {
                for (TransitionStats s : actionEntry.values()) {
                    s.count *= 0.97;
                    s.success *= 0.97;
                    s.failure *= 0.97;
                }
            }
        }
    }

    // ===================================================
    public static double applyLoopPenalty(String state, double score) {

        if (LoopDetector.isLooping(state)) {
            return score - 3;
        }

        return score;
    }

    // ===================================================
    public static double getActionScore(String state, String action) {

        return getHybridScore(state, action);
    }

    // ===================================================
    public static Map<String, Double> getQTable() {
        return qTable;
    }

    // ===================================================
    public static void reset() {
        graph.clear();
        qTable.clear();
        explorationRate = 0.12;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}