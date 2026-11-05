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

    private static final double LR = 0.25;
    private static final double DISCOUNT = 0.9;
    private static final double DECAY = 0.97;

    private static double explorationRate = 0.1;

    // ================= STATS =================
    private static class TransitionStats {

        double count;
        double success;
        double failure;
        long lastSeen;

        synchronized void record(boolean ok) {
            count++;
            if (ok) success++;
            else failure++;
            lastSeen = System.currentTimeMillis();
        }

        synchronized double score() {

            if (count == 0) return 0.4;

            double ratio = (success + 1) / (count + 2);

            double age = (System.currentTimeMillis() - lastSeen) / 10000.0;
            double recency = 1.0 / (1.0 + age);

            double confidence = Math.min(1.0, count / 15.0);

            return (ratio * 0.6)
                    + (recency * 0.2)
                    + (confidence * 0.2);
        }
    }

    // ===================================================
    // 🔥 RECORD
    // ===================================================
    public static void record(State state,
                              String action,
                              State nextState,
                              String toUrl,
                              boolean success,
                              double reward) {

        if (state == null || nextState == null || action == null) return;

        String s = state.signature();
        String next = nextState.signature();

        graph
                .computeIfAbsent(s, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(action, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(toUrl, k -> new TransitionStats())
                .record(success);

        updateQ(s, action, next, reward);
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

        newQ = clamp(newQ, -5, 5);

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

        double exploration = 0;

        if (Math.random() < explorationRate) {
            exploration = (1 - Math.abs(q)) * 0.5;
        }

        return q + graphScore + exploration;
    }

    // ===================================================
    // 🔥 FIXED (State-based)
    // ===================================================
    public static Optional<String> suggestAction(State state) {

        if (state == null) return Optional.empty();

        String s = state.signature();

        Map<String, Map<String, TransitionStats>> actions = graph.get(s);

        if (actions == null || actions.isEmpty())
            return Optional.empty();

        return actions.keySet().stream()
                .max(Comparator.comparingDouble(a ->
                        applyLoopPenalty(state,
                                getHybridScore(s, a))));
    }

    // ===================================================
    public static Optional<String> suggestNextUrl(State state,
                                                  String action) {

        if (state == null || action == null)
            return Optional.empty();

        String s = state.signature();

        Map<String, TransitionStats> targets =
                graph.getOrDefault(s, Map.of())
                        .getOrDefault(action, Map.of());

        return targets.entrySet().stream()
                .max(Comparator.comparingDouble(e ->
                        e.getValue().score()))
                .map(Map.Entry::getKey);
    }

    // ===================================================
    public static void adjustExploration(boolean success) {

        if (success) explorationRate *= 0.97;
        else explorationRate *= 1.02;

        explorationRate = clamp(explorationRate, 0.05, 0.25);
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
    // 💣 FIXED
    // ===================================================
    public static double applyLoopPenalty(State state, double score) {

        if (LoopDetector.isLooping(state)) {
            return score - 2.5;
        }

        return score;
    }

    // ===================================================
    public static double getActionScore(State state, String action) {

        if (state == null || action == null) return 0;

        return getHybridScore(state.signature(), action);
    }

    // ===================================================
    public static void reset() {
        graph.clear();
        qTable.clear();
        explorationRate = 0.1;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}