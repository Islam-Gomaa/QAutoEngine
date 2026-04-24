package engine.learning;

import engine.state.SessionState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LearningEngine {

    // 🔥 global knowledge
    private static final Map<String, Double> actionScores =
            new ConcurrentHashMap<>();

    // ===================================================
    // 🧠 LEARN FROM CURRENT SESSION
    // ===================================================
    public static void learn() {

        Map<String, Integer> stats = SessionState.getActionStats();

        for (String action : stats.keySet()) {

            int success = stats.getOrDefault(action, 0);
            int failures = SessionState.getFailureCount(action);

            double score = calculateScore(success, failures);

            actionScores.put(action, score);

            System.out.println("📚 Learning → " + action + " = " + score);
        }
    }

    // ===================================================
    // 🔢 SCORE FORMULA
    // ===================================================
    private static double calculateScore(int success, int failures) {

        if (success == 0 && failures == 0) return 0;

        return success - (failures * 2.0);
    }

    // ===================================================
    // 🎯 GET SCORE
    // ===================================================
    public static double getScore(String action) {
        return actionScores.getOrDefault(action, 0.0);
    }

    // ===================================================
    // 🔥 DECISION HELPER
    // ===================================================
    public static boolean isActionAllowed(String action) {

        double score = getScore(action);

        if (score < -2) {
            System.out.println("🚫 AI blocked action: " + action);
            return false;
        }

        return true;
    }

    // ===================================================
    // 🧹 RESET
    // ===================================================
    public static void reset() {
        actionScores.clear();
    }

    // ===================================================
    // 🌐 PATH LEARNING (NEW 🔥)
    // ===================================================
    private static final Map<String, Double> pathScores =
            new ConcurrentHashMap<>();

    public static void learnPath(String url, boolean success) {

        if (url == null || url.isEmpty()) return;

        double delta = success ? 2.0 : -1.5;

        pathScores.merge(url, delta, Double::sum);

        System.out.println("🌐 Path Learning → " + url + " = " + pathScores.get(url));
    }

    public static double getPathScore(String url) {
        return pathScores.getOrDefault(url, 0.0);
    }

    public static void decay() {
        // تقليل تأثير القديم (مبدئي)
        // حالياً ممكن تسيبها فاضية أو تعمل logging
        System.out.println("🧠 Decay applied");
    }
}