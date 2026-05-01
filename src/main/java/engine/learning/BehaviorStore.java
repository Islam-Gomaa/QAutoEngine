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
    // 📊 STATS MODEL
    // ===================================================
    private static class Stats {
        int success;
        int failure;
        long lastUpdated;

        void record(boolean ok) {
            if (ok) success++;
            else failure++;

            lastUpdated = System.currentTimeMillis();
        }

        double score() {

            int total = success + failure;

            if (total == 0) return 0;

            double ratio = (double) success / total;

            // 🔥 recency factor
            double age = (System.currentTimeMillis() - lastUpdated) / 10000.0;
            double recency = 1.0 / (1.0 + age);

            // 🔥 confidence
            double confidence = Math.min(1.0, total / 10.0);

            return ratio * confidence + recency * 0.3;
        }
    }

    // ===================================================
    // 🔥 BUILD KEY (state + action)
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

        String key = buildKey(state, action);

        Stats stats = store.get(key);

        if (stats == null) return 0;

        return stats.score();
    }

    // ===================================================
    // 📊 GET RAW DATA
    // ===================================================
    public static int getSuccess(String state, String action) {
        Stats s = store.get(buildKey(state, action));
        return s == null ? 0 : s.success;
    }

    public static int getFailure(String state, String action) {
        Stats s = store.get(buildKey(state, action));
        return s == null ? 0 : s.failure;
    }

    // ===================================================
    // 🧠 DECAY (IMPORTANT)
    // ===================================================
    public static void decay() {

        for (Stats s : store.values()) {

            s.success *= 0.95;
            s.failure *= 0.95;
        }
    }

    // ===================================================
    // 🧠 GET ALL KEYS
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
}