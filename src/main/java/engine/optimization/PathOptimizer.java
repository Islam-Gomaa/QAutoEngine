package engine.optimization;

import engine.learning.LearningEngine;
import engine.state.SessionState;

import java.util.*;

public class PathOptimizer {

    // ===================================================
    // 🔥 CONFIG
    // ===================================================
    private static final double EXPLORATION_RATE = 0.2;

    private static final Map<String, Integer> KEYWORD_WEIGHTS = new HashMap<>();
    private static final List<String> LOW_VALUE = Arrays.asList(
            "login", "logout", "privacy", "terms", "error"
    );

    static {
        // 🔥 core
        KEYWORD_WEIGHTS.put("dashboard", 5);
        KEYWORD_WEIGHTS.put("home", 4);

        // 🔥 business
        KEYWORD_WEIGHTS.put("product", 5);
        KEYWORD_WEIGHTS.put("category", 4);
        KEYWORD_WEIGHTS.put("details", 4);
        KEYWORD_WEIGHTS.put("view", 3);
        KEYWORD_WEIGHTS.put("list", 3);

        // 🔥 flows
        KEYWORD_WEIGHTS.put("cart", 4);
        KEYWORD_WEIGHTS.put("checkout", 5);
        KEYWORD_WEIGHTS.put("payment", 5);
        KEYWORD_WEIGHTS.put("order", 4);

        // 🔥 account
        KEYWORD_WEIGHTS.put("profile", 3);
        KEYWORD_WEIGHTS.put("account", 3);
        KEYWORD_WEIGHTS.put("settings", 2);

        // 🔥 CRUD
        KEYWORD_WEIGHTS.put("create", 3);
        KEYWORD_WEIGHTS.put("edit", 3);
        KEYWORD_WEIGHTS.put("update", 3);
    }

    // ===================================================
    // 🧠 MAIN
    // ===================================================
    public static List<String> prioritize(List<String> urls) {

        if (urls == null || urls.isEmpty()) return Collections.emptyList();

        Map<String, Double> scores = new HashMap<>();

        for (String url : urls) {
            scores.put(url, calculateScore(url));
        }

        List<String> sorted = new ArrayList<>(urls);

        sorted.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));

        return sorted;
    }

    // ===================================================
    // 🔢 SCORE ENGINE
    // ===================================================
    private static double calculateScore(String url) {

        if (url == null || url.isEmpty()) return -100;

        double score = 0;

        // ==============================
        // ❌ VISITED PENALTY
        // ==============================
        if (SessionState.isUrlVisited(url)) {
            score -= 10;
        }

        // ==============================
        // 🔁 LOOP PENALTY
        // ==============================
        score -= countVisits(url) * 2;

        // ==============================
        // 🚫 LOW VALUE
        // ==============================
        if (isLowValue(url)) {
            score -= 5;
        }

        // ==============================
        // 🔥 IMPORTANCE (WEIGHTED)
        // ==============================
        score += importanceScore(url);

        // ==============================
        // 🧠 DEPTH CONTROL
        // ==============================
        int depth = getDepth(url);
        score += Math.max(0, 3 - depth);

        // ==============================
        // 📈 REAL LEARNING (🔥 FIX)
        // ==============================
        score += LearningEngine.getPathScore(url);

        // ==============================
        // 🎲 EXPLORATION
        // ==============================
        if (Math.random() < EXPLORATION_RATE) {
            score += 3;
        }

        return score;
    }

    // ===================================================
    // 🔥 IMPORTANCE
    // ===================================================
    private static double importanceScore(String url) {

        url = url.toLowerCase();
        double score = 0;

        for (Map.Entry<String, Integer> entry : KEYWORD_WEIGHTS.entrySet()) {
            if (url.contains(entry.getKey())) {
                score += entry.getValue();
            }
        }

        return score;
    }

    // ===================================================
    // 🚫 LOW VALUE
    // ===================================================
    private static boolean isLowValue(String url) {

        url = url.toLowerCase();

        for (String bad : LOW_VALUE) {
            if (url.contains(bad)) return true;
        }

        return false;
    }

    // ===================================================
    // 🔁 VISIT COUNT
    // ===================================================
    private static int countVisits(String url) {

        List<String> path = SessionState.getNavigationPath();
        int count = 0;

        for (String p : path) {
            if (p.equals(url)) count++;
        }

        return count;
    }

    // ===================================================
    // 🧠 DEPTH
    // ===================================================
    private static int getDepth(String url) {

        if (url == null) return 0;

        return url.split("/").length;
    }
}