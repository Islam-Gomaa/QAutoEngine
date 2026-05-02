package ai.intelligence;

import java.util.*;

public class ScenarioTracker {

    // ===============================
    // 🧠 STEP MODEL
    // ===============================
    public static class Step {
        public String action;
        public String url;
        public double progressScore;
        public long timestamp;

        public Step(String action, String url, double progressScore) {
            this.action = action;
            this.url = url;
            this.progressScore = progressScore;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return action + " → " + url + " (score=" + progressScore + ")";
        }
    }

    // ===============================
    // 🔥 DATA
    // ===============================
    private static final List<Step> steps = new ArrayList<>();
    private static final Set<String> visited = new HashSet<>();
    private static final Map<String, Integer> urlVisits = new HashMap<>();

    // ===============================
    // ➕ RECORD STEP
    // ===============================
    public static void record(String action, String url, double progressScore) {

        Step step = new Step(action, url, progressScore);
        steps.add(step);

        visited.add(url);
        urlVisits.merge(url, 1, Integer::sum);
    }

    // ===============================
    // 🔁 LOOP DETECTION
    // ===============================
    public static boolean isLooping(String url) {
        return urlVisits.getOrDefault(url, 0) > 2;
    }

    // ===============================
    // 🎯 MILESTONES
    // ===============================
    public static List<Step> getMilestones() {

        List<Step> result = new ArrayList<>();

        for (Step step : steps) {
            if (step.progressScore > 3) {
                result.add(step);
            }
        }

        return result;
    }

    // ===============================
    // 🧠 BEST PATH EXTRACTION
    // ===============================
    public static List<Step> getBestPath() {

        List<Step> best = new ArrayList<>();

        for (Step step : steps) {
            if (!isLooping(step.url)) {
                best.add(step);
            }
        }

        return best;
    }

    // ===============================
    // 📊 QUALITY SCORE
    // ===============================
    public static double getScenarioScore() {

        if (steps.isEmpty()) return 0;

        double total = 0;

        for (Step step : steps) {
            total += step.progressScore;
        }

        return total / steps.size();
    }

    // ===============================
    // 🧾 PRINT SMART SCENARIO
    // ===============================
    public static void printSmart() {

        System.out.println("\n===== 🧠 SMART SCENARIO =====");

        List<Step> path = getBestPath();

        for (Step step : path) {
            System.out.println(step);
        }

        System.out.println("\n📊 Score: " + getScenarioScore());
        System.out.println("🔁 Unique URLs: " + visited.size());

        System.out.println("=============================\n");
    }

    // ===============================
    // 🧹 RESET
    // ===============================
    public static void reset() {
        steps.clear();
        visited.clear();
        urlVisits.clear();
    }
}