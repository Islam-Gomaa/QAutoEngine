package engine.learning;

import engine.learning.LearningEngine.State;

public class RewardEngine {

    public static double calculate(State prev,
                                   State next,
                                   String action,
                                   boolean success,
                                   boolean revisited) {

        double reward = 0;

        // ================= BASE =================
        reward += success ? 2.0 : -1.5;

        // ================= STATE CHANGE =================
        if (prev != null && next != null &&
                !safe(prev.pageType).equals(safe(next.pageType))) {
            reward += 2.0;
        }

        // ================= DOM CHANGE =================
        int prevDom = prev != null ? prev.domSize : 0;
        int nextDom = next != null ? next.domSize : 0;

        int delta = Math.abs(nextDom - prevDom);

        if (delta > 50) reward += 1.5;
        else if (delta > 10) reward += 0.8;

        // ================= GOAL PROGRESSION =================
        if (prev != null && prev.goal != null) {

            switch (prev.goal) {

                case ADD_TO_CART:
                    if ("product".equals(next.pageType)) reward += 1;
                    if ("cart".equals(next.pageType)) reward += 4;
                    break;

                case REACH_CHECKOUT:
                    if ("cart".equals(next.pageType)) reward += 2;
                    if ("checkout".equals(next.pageType)) reward += 6;
                    break;

                case COMPLETE_PAYMENT:
                    if ("checkout".equals(next.pageType)) reward += 3;
                    if ("payment".equals(next.pageType)) reward += 10;
                    break;
            }
        }

        // ================= ACTION QUALITY =================
        if ("FormAction".equals(action)) reward += 1.5;
        if ("NavigationAction".equals(action)) reward += 1.0;
        if ("ClickAction".equals(action)) reward += 0.5;

        // ================= LOOP PENALTY =================
        if (revisited) reward -= 3;

        // ================= EXPLORATION =================
        if (Math.random() < 0.1) reward += 0.5;

        return reward;
    }

    // ================= SAFE HELPER =================
    private static String safe(String s) {
        return s == null ? "" : s;
    }
}