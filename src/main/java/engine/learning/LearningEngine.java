package engine.learning;

import engine.context.ContextEngine;
import engine.goal.GoalEngine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LearningEngine {

    // ===================================================
    // 🧠 STATE MODEL
    // ===================================================
    public static class State {

        public ContextEngine.ContextType context;
        public String pageType;
        public GoalEngine.GoalType goal;
        public int domSize;

        public State(ContextEngine.ContextType context,
                     String url,
                     GoalEngine.GoalType goal,
                     int domSize) {

            this.context = context;
            this.pageType = simplify(url);
            this.goal = goal;
            this.domSize = domSize;
        }

        private String simplify(String url) {

            if (url == null) return "generic";

            url = url.toLowerCase();

            if (url.contains("product")) return "product";
            if (url.contains("cart")) return "cart";
            if (url.contains("checkout")) return "checkout";
            if (url.contains("payment")) return "payment";

            return "generic";
        }

        @Override
        public String toString() {
            return context + "|" + pageType + "|" + goal;
        }
    }

    // ===================================================
    // 🧠 REWARD ENGINE (🔥 مهم جدًا)
    // ===================================================
    private static class RewardEngine {

        static double calculate(State prev,
                                State next,
                                String action,
                                boolean success,
                                boolean revisited) {

            double reward = 0;

            // BASE
            reward += success ? 2.0 : -1.5;

            // STATE CHANGE
            if (!prev.pageType.equals(next.pageType)) {
                reward += 2.0;
            }

            // DOM CHANGE
            int delta = Math.abs(next.domSize - prev.domSize);
            if (delta > 50) reward += 1.5;
            else if (delta > 10) reward += 0.8;

            // GOAL PROGRESSION
            if (prev.goal != null) {

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

            // ACTION QUALITY
            if ("FormAction".equals(action)) reward += 1.5;
            if ("NavigationAction".equals(action)) reward += 1.0;
            if ("ClickAction".equals(action)) reward += 0.5;

            // LOOP PENALTY
            if (revisited) reward -= 3;

            // SMALL EXPLORATION BONUS
            if (Math.random() < 0.1) reward += 0.5;

            return reward;
        }
    }

    // ===================================================
    private static final Map<String, Double> lastRewards =
            new ConcurrentHashMap<>();

    // ===================================================
    // 🧠 BUILD STATE
    // ===================================================
    public static State buildState(ContextEngine.ContextType context,
                                   String url,
                                   GoalEngine.GoalType goal,
                                   int domSize) {

        return new State(context, url, goal, domSize);
    }

    // ===================================================
    // 🧠 MAIN LEARNING ENTRY (🔥 FIXED)
    // ===================================================
    public static void learn(State prevState,
                             State nextState,
                             String action,
                             String nextUrl,
                             boolean success,
                             boolean revisited) {

        double reward = RewardEngine.calculate(
                prevState,
                nextState,
                action,
                success,
                revisited
        );

        // 🔥 FIX: send reward to BehaviorGraph
        BehaviorGraph.record(
                prevState.toString(),
                action,
                nextState.toString(),
                nextUrl,
                success,
                reward
        );

        // optional cache
        lastRewards.put(prevState + "|" + action, reward);

        System.out.println("🧠 Learning Update:");
        System.out.println("   From: " + prevState);
        System.out.println("   To:   " + nextState);
        System.out.println("   Action: " + action);
        System.out.println("   Reward: " + reward);
    }

    // ===================================================
    // 🧠 DECISION HELPER
    // ===================================================
    public static boolean shouldExecute(State state,
                                        String action) {

        return BehaviorGraph
                .suggestAction(state.toString())
                .map(best -> best.equals(action))
                .orElse(true);
    }

    // ===================================================
    public static double getScore(State state, String action) {

        return BehaviorGraph.getActionScore(
                state.toString(),
                action
        );
    }

    // ===================================================
    public static void reset() {

        BehaviorGraph.reset();
        lastRewards.clear();

        System.out.println("🧹 LearningEngine Reset");
    }
}