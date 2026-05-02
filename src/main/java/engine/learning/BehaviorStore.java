package engine.learning;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BehaviorStore {

    // ===================================================
    // 🧠 STRUCTURE: key = state|action
    // ===================================================
    private static final Map<String, Stats> store =
            new ConcurrentHashMap<>();

    // ===================================================
    // 📊 STATS MODEL (THREAD SAFE + RL READY)
    // ===================================================
    private static class Stats {

        double success;
        double failure;
        long lastUpdated;

        synchronized void record(boolean ok) {
            if (ok) success += 1;
            else failure += 1;

            lastUpdated = System.currentTimeMillis();
        }

        synchronized double score() {

            double total = success + failure;

            if (total == 0) return 0.5; // neutral

            // 🔥 smoothing (prevents spikes)
            double smoothedSuccess = success + 1;
            double smoothedTotal = total + 2;

            double ratio = smoothedSuccess / smoothedTotal;

            // 🔥 recency (bounded)
            double age = (System.currentTimeMillis() - lastUpdated) / 10000.0;
            double recency = 1.0 / (1.0 + age);
            recency = clamp(recency, 0.1, 1.0);

            // 🔥 confidence
            double confidence = Math.min(1.0, total / 15.0);

            // 🔥 failure penalty
            double failurePenalty = failure / (total + 1);

            double score =
                    (ratio * 0.6)
                            + (recency * 0.2)
                            + (confidence * 0.2)
                            - (failurePenalty * 0.3);

            return clamp(score, 0.0, 1.0);
        }

        synchronized void decay() {
            success *= 0.98;
            failure *= 0.98;
        }
    }

    // ===================================================
    // 🔥 BUILD KEY
    // ===================================================
    public static String buildKey(String state, String action) {
        return state + "|" + action;
    }

    // ===================================================
    // ✅ RECORD
    // ===================================================
    public static void record(String state,
                              String action,
                              boolean success) {

        String key = buildKey(state, action);

        store
                .computeIfAbsent(key, k -> new Stats())
                .record(success);
    }

    // ===================================================
    // 📊 GET SCORE
    // ===================================================
    public static double getScore(String state, String action) {

        Stats stats = store.get(buildKey(state, action));

        if (stats == null) return 0.5;

        return stats.score();
    }

    // ===================================================
    // 📊 RAW DATA
    // ===================================================
    public static int getSuccess(String state, String action) {
        Stats s = store.get(buildKey(state, action));
        return s == null ? 0 : (int) s.success;
    }

    public static int getFailure(String state, String action) {
        Stats s = store.get(buildKey(state, action));
        return s == null ? 0 : (int) s.failure;
    }

    // ===================================================
    // 🧠 DECAY (SAFE)
    // ===================================================
    public static void decay() {

        for (Stats s : store.values()) {
            s.decay();
        }
    }

    // ===================================================
    // 🧠 CLEAN LOW VALUE (🔥 مهم جدًا)
    // ===================================================
    public static void cleanup() {

        store.entrySet().removeIf(e -> {
            Stats s = e.getValue();
            return (s.success + s.failure) < 2 &&
                    (System.currentTimeMillis() - s.lastUpdated) > 60000;
        });
    }

    // ===================================================
    // 🧠 KEYS
    // ===================================================
    public static Set<String> getAllKeys() {
        return store.keySet();
    }

    // ===================================================
    // 🧹 RESET
    // ===================================================
    public static void reset() {
        store.clear();
    }

    // ===================================================
    // 🔧 UTILS
    // ===================================================
    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}