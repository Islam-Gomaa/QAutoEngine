package engine.learning;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BehaviorStore {

    // 🔥 success count
    private static final Map<String, Integer> successMap = new ConcurrentHashMap<>();

    // 🔥 failure count
    private static final Map<String, Integer> failureMap = new ConcurrentHashMap<>();

    // ===================================================
    // ✅ SUCCESS
    // ===================================================
    public static void recordSuccess(String key) {

        successMap.merge(key, 1, Integer::sum);
    }

    // ===================================================
    // ❌ FAILURE
    // ===================================================
    public static void recordFailure(String key) {

        failureMap.merge(key, 1, Integer::sum);
    }

    // ===================================================
    // 📊 GETTERS
    // ===================================================
    public static int getSuccess(String key) {
        return successMap.getOrDefault(key, 0);
    }

    public static int getFailure(String key) {
        return failureMap.getOrDefault(key, 0);
    }

    // ===================================================
    // 🧠 SCORE
    // ===================================================
    public static double getScore(String key) {

        int success = getSuccess(key);
        int failure = getFailure(key);

        int total = success + failure;

        if (total == 0) return 0.5; // neutral

        return (double) success / total;
    }
}