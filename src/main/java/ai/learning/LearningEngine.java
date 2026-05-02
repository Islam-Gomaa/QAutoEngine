package ai.learning;

import engine.context.ContextEngine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LearningEngine {

    // ===================================================
    // 🧠 STATE MODEL (UPGRADED 🔥)
    // ===================================================
    public static class State {

        public ContextEngine.ContextType context;
        public String pageType;
        public int domSize;
        public int depth;
        public boolean revisited;

        public State(ContextEngine.ContextType context,
                     String url,
                     int domSize,
                     int depth,
                     boolean revisited) {

            this.context = context;
            this.pageType = simplify(url);
            this.domSize = domSize;
            this.depth = depth;
            this.revisited = revisited;
        }

        private String simplify(String url) {

            if (url == null) return "generic";

            url = url.toLowerCase();

            if (url.contains("login")) return "login";
            if (url.contains("dashboard")) return "dashboard";
            if (url.contains("form")) return "form";
            if (url.contains("detail")) return "detail";
            if (url.contains("success")) return "success";

            return "generic";
        }

        @Override
        public String toString() {
            return context + "|" + pageType + "|" + depth;
        }
    }

    // ===================================================
    // 🔥 LAST REWARD CACHE
    // ===================================================
    private static final Map<String, Double> lastRewards =
            new ConcurrentHashMap<>();

    // ===================================================
    // 🧠 BUILD STATE
    // ===================================================
    public static State buildState(ContextEngine.ContextType context,
                                   String url,
                                   int domSize,
                                   int depth,
                                   boolean revisited) {

        return new State(context, url, domSize, depth, revisited);
    }

    // ===================================================
    // 🧠 MAIN LEARNING (ULTRA 🔥)
    // ===================================================
    public static void learn(State prevState,
                             State nextState,
                             String action,
                             String nextUrl,
                             boolean success,
                             double progressScore,
                             boolean terminal) {

        // 🔥 advanced reward
        double reward = RewardEngine.calculate(
                prevState,
                nextState,
                action,
                success,
                prevState.revisited,
                progressScore,
                terminal
        );

        // 🧠 update graph
        BehaviorGraph.record(
                prevState.toString(),
                action,
                nextState.toString(),
                nextUrl,
                success,
                reward
        );

        // 🧠 update memory
        BehaviorStore.record(
                prevState.toString(),
                action,
                success
        );

        // 🧠 exploration tuning
        BehaviorGraph.adjustExploration(success);

        // cache
        lastRewards.put(prevState + "|" + action, reward);

        // decay
        BehaviorGraph.decayAll();
        BehaviorStore.decay();

        // cleanup
        BehaviorStore.cleanup();

        // debug
        log(prevState, nextState, action, reward, progressScore);
    }

    // ===================================================
    private static void log(State prev,
                            State next,
                            String action,
                            double reward,
                            double progress) {

        System.out.println("🧠 Learning Update:");
        System.out.println("   From: " + prev);
        System.out.println("   To:   " + next);
        System.out.println("   Action: " + action);
        System.out.println("   Progress: " + progress);
        System.out.println("   Reward: " + reward);
    }

    // ===================================================
    // 🧠 DECISION SUPPORT
    // ===================================================
    public static double getScore(State state, String action) {

        return BehaviorGraph.getActionScore(
                state.toString(),
                action
        ) + BehaviorStore.getScore(state.toString(), action);
    }

    // ===================================================
    public static boolean shouldExecute(State state, String action) {

        return BehaviorGraph
                .suggestAction(state.toString())
                .map(best -> best.equals(action))
                .orElse(true);
    }

    // ===================================================
    public static double getLastReward(State state, String action) {
        return lastRewards.getOrDefault(state + "|" + action, 0.0);
    }

    // ===================================================
    public static void reset() {

        BehaviorGraph.reset();
        BehaviorStore.reset();
        lastRewards.clear();

        System.out.println("🧹 LearningEngine Reset");
    }
}