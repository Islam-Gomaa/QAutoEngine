package ai.learning;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BehaviorStore {

    // ===================================================
    // 🧠 STRUCTURE: key = stateSignature|action
    // ===================================================
    private static final Map<String, Stats> store =
            new ConcurrentHashMap<>();

    // ===================================================
    // 📊 STATS MODEL (V6)
    // ===================================================
    private static class Stats {

        double success;
        double failure;
        double rewardSum;

        long lastUpdated;

        synchronized void record(boolean ok, double reward) {

            if (ok) success += 1;
            else failure += 1;

            rewardSum += reward;

            lastUpdated = System.currentTimeMillis();
        }

        synchronized double score() {

            double total = success + failure;

            if (total == 0) return 0.4;

            double ratio = (success + 1) / (total + 2);

            double avgReward = rewardSum / Math.max(1, total);

            double age = (System.currentTimeMillis() - lastUpdated) / 10000.0;
            double recency = 1.0 / (1.0 + age);
            recency = clamp(recency, 0.2, 1.0);

            double confidence = Math.min(1.0, total / 20.0);

            double score =
                    (ratio * 0.5)
                            + (avgReward * 0.2)
                            + (recency * 0.2)
                            + (confidence * 0.1);

            return clamp(score, 0.0, 1.0);
        }

        synchronized void decay() {
            success *= 0.97;
            failure *= 0.97;
            rewardSum *= 0.97;
        }
    }

    // ===================================================
    // 🔥 BUILD KEY
    // ===================================================
    private static String buildKey(State state, String action) {

        if (state == null || action == null) return "NULL|NULL";

        return state.signature() + "|" + action;
    }

    // ===================================================
    // ✅ RECORD
    // ===================================================
    public static void record(State state,
                              String action,
                              boolean success,
                              double reward) {

        if (state == null || action == null) return;

        String key = buildKey(state, action);

        store
                .computeIfAbsent(key, k -> new Stats())
                .record(success, reward);
    }

    // ===================================================
    // 📊 GET SCORE
    // ===================================================
    public static double getScore(State state, String action) {

        if (state == null || action == null) return 0.4;

        Stats stats = store.get(buildKey(state, action));

        if (stats == null) return 0.4;

        return stats.score();
    }

    // ===================================================
    // 📊 RAW DATA
    // ===================================================
    public static int getSuccess(State state, String action) {

        if (state == null || action == null) return 0;

        Stats s = store.get(buildKey(state, action));
        return s == null ? 0 : (int) s.success;
    }

    public static int getFailure(State state, String action) {

        if (state == null || action == null) return 0;

        Stats s = store.get(buildKey(state, action));
        return s == null ? 0 : (int) s.failure;
    }

    // ===================================================
    // 🧠 DECAY
    // ===================================================
    public static void decay() {
        for (Stats s : store.values()) {
            s.decay();
        }
    }

    // ===================================================
    // 🧠 CLEANUP
    // ===================================================
    public static void cleanup() {

        store.entrySet().removeIf(e -> {
            Stats s = e.getValue();

            return (s.success + s.failure) < 2 &&
                    (System.currentTimeMillis() - s.lastUpdated) > 60000;
        });
    }

    // ===================================================
    public static Set<String> getAllKeys() {
        return store.keySet();
    }

    // ===================================================
    public static void reset() {
        store.clear();
    }

    // ===================================================
    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}