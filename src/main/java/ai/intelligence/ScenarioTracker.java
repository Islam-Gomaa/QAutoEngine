package ai.intelligence;

import ai.learning.State;

import java.util.*;

public class ScenarioTracker {

    // ===================================================
    // 🧠 STEP MODEL (V6)
    // ===================================================
    public static class Step {

        public final String action;
        public final String url;
        public final String stateSignature;
        public final double progressScore;

        public final long timestamp;

        public Step(String action,
                    String url,
                    State state,
                    double progressScore) {

            this.action = action;
            this.url = url;
            this.stateSignature = state != null ? state.signature() : "UNKNOWN";
            this.progressScore = progressScore;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return action +
                    " → " + url +
                    " | state=" + stateSignature +
                    " | score=" + progressScore;
        }
    }

    // ===================================================
    // 🔥 DATA
    // ===================================================
    private static final List<Step> steps = new ArrayList<>();
    private static final Map<String, Integer> stateVisits = new HashMap<>();

    // ===================================================
    // ➕ RECORD
    // ===================================================
    public static void record(String action,
                              String url,
                              State state,
                              double progressScore) {

        Step step = new Step(action, url, state, progressScore);

        steps.add(step);

        String sig = step.stateSignature;

        stateVisits.merge(sig, 1, Integer::sum);
    }

    // ===================================================
    // 🔁 LOOP DETECTION (V6)
    // ===================================================
    public static boolean isLooping(State state) {

        if (state == null) return false;

        return stateVisits.getOrDefault(state.signature(), 0) > 2;
    }

    // ===================================================
    // 🔥 MILESTONES (dynamic)
    // ===================================================
    public static List<Step> getMilestones() {

        List<Step> result = new ArrayList<>();

        double avg = getScenarioScore();

        for (Step step : steps) {
            if (step.progressScore > avg + 1) {
                result.add(step);
            }
        }

        return result;
    }

    // ===================================================
    // 🧠 BEST PATH (filtered + ordered)
    // ===================================================
    public static List<Step> getBestPath() {

        List<Step> best = new ArrayList<>();

        Set<String> visitedStates = new HashSet<>();

        for (Step step : steps) {

            if (!visitedStates.contains(step.stateSignature)) {

                best.add(step);
                visitedStates.add(step.stateSignature);
            }
        }

        return best;
    }

    // ===================================================
    // 💣 SEGMENTATION (scenarios chunks)
    // ===================================================
    public static List<List<Step>> segmentScenarios() {

        List<List<Step>> segments = new ArrayList<>();
        List<Step> current = new ArrayList<>();

        for (Step step : steps) {

            current.add(step);

            if (step.progressScore > 4) {
                segments.add(new ArrayList<>(current));
                current.clear();
            }
        }

        if (!current.isEmpty()) {
            segments.add(current);
        }

        return segments;
    }

    // ===================================================
    // 📊 QUALITY SCORE
    // ===================================================
    public static double getScenarioScore() {

        if (steps.isEmpty()) return 0;

        double total = 0;

        for (Step step : steps) {
            total += step.progressScore;
        }

        return total / steps.size();
    }

    // ===================================================
    // 🔥 BEST SCENARIO (highest quality segment)
    // ===================================================
    public static List<Step> getBestScenario() {

        List<List<Step>> segments = segmentScenarios();

        double bestScore = -999;
        List<Step> best = new ArrayList<>();

        for (List<Step> seg : segments) {

            double score = seg.stream()
                    .mapToDouble(s -> s.progressScore)
                    .average()
                    .orElse(0);

            if (score > bestScore) {
                bestScore = score;
                best = seg;
            }
        }

        return best;
    }

    // ===================================================
    // 🧾 PRINT SMART SCENARIO (UPGRADED)
    // ===================================================
    public static void printSmart() {

        System.out.println("\n===== 🧠 BEST SCENARIO =====");

        List<Step> path = getBestScenario();

        for (Step step : path) {
            System.out.println(step);
        }

        System.out.println("\n📊 Score: " + getScenarioScore());
        System.out.println("🔁 Unique States: " + stateVisits.size());

        System.out.println("=============================\n");
    }

    // ===================================================
    // 🧹 RESET
    // ===================================================
    public static void reset() {
        steps.clear();
        stateVisits.clear();
    }
}