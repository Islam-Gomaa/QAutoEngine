package ai.learning;

import ai.learning.LearningEngine.State;

public class RewardEngine {

    // 🔥 dynamic learning factor
    private static double adaptiveFactor = 1.0;

    // ===============================
    // 🧠 MAIN REWARD FUNCTION
    // ===============================
    public static double calculate(State prev,
                                   State next,
                                   String action,
                                   boolean success,
                                   boolean revisited,
                                   double progressScore,
                                   boolean terminal) {

        double reward = 0;

        // ===============================
        // 1️⃣ BASE SUCCESS / FAILURE
        // ===============================
        reward += success ? 2.0 : -2.5;

        // ===============================
        // 2️⃣ PROGRESS (🔥 أهم جزء)
        // ===============================
        reward += progressScore * 2;

        // ===============================
        // 3️⃣ STRONG PROGRESS BOOST
        // ===============================
        if (progressScore > 4) reward += 3;
        else if (progressScore > 2) reward += 1.5;

        // ===============================
        // 4️⃣ STATE TRANSITION
        // ===============================
        if (prev != null && next != null &&
                !safe(prev.pageType).equals(safe(next.pageType))) {

            reward += 4;
        }

        // ===============================
        // 5️⃣ DOM INTELLIGENCE
        // ===============================
        int prevDom = prev != null ? prev.domSize : 0;
        int nextDom = next != null ? next.domSize : 0;

        int delta = Math.abs(nextDom - prevDom);

        if (delta > 80) reward += 2;
        else if (delta > 30) reward += 1;

        // ===============================
        // 6️⃣ ACTION QUALITY
        // ===============================
        reward += actionWeight(action, progressScore);

        // ===============================
        // 7️⃣ LOOP / REVISIT PENALTY
        // ===============================
        if (revisited) reward -= 4;

        // ===============================
        // 8️⃣ STAGNATION PENALTY
        // ===============================
        if (progressScore < 1) reward -= 2;

        // ===============================
        // 9️⃣ TERMINAL REWARD (💣 مهم جدًا)
        // ===============================
        if (terminal) reward += 15;

        // ===============================
        // 🔟 ADAPTIVE LEARNING
        // ===============================
        adaptiveFactor = updateAdaptiveFactor(success, progressScore);

        reward *= adaptiveFactor;

        // ===============================
        // 🔥 NORMALIZATION
        // ===============================
        reward = normalize(reward);

        return reward;
    }

    // ===============================
    // 🧠 ACTION INTELLIGENCE
    // ===============================
    private static double actionWeight(String action, double progressScore) {

        double weight = 0;

        switch (action) {

            case "FormAction":
                weight += 2;
                if (progressScore > 2) weight += 1;
                break;

            case "NavigationAction":
                weight += 1.5;
                break;

            case "ClickAction":
                weight += 0.5;
                break;

            default:
                weight += 0.2;
        }

        return weight;
    }

    // ===============================
    // 🔥 ADAPTIVE FACTOR
    // ===============================
    private static double updateAdaptiveFactor(boolean success,
                                               double progressScore) {

        if (success && progressScore > 2) {
            adaptiveFactor *= 1.05;
        } else {
            adaptiveFactor *= 0.97;
        }

        return clamp(adaptiveFactor, 0.5, 2.0);
    }

    // ===============================
    // 🧠 NORMALIZATION (prevents explosion)
    // ===============================
    private static double normalize(double value) {
        return Math.tanh(value);
    }

    // ===============================
    // 🔧 UTILS
    // ===============================
    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}