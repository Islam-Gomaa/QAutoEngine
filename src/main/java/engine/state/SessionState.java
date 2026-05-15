package engine.state;

import ai.learning.State;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class SessionState {

    // ===================================================
    // 🌐 VISITED
    // ===================================================
    private static final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private static final Set<String> visitedElements = ConcurrentHashMap.newKeySet();

    private static final Map<String, Integer> elementVisitCount = new ConcurrentHashMap<>();
    private static final Map<String, Integer> urlVisitCount = new ConcurrentHashMap<>();

    // ===================================================
    // 🧠 STATE TRACKING
    // ===================================================
    private static final Map<String, Integer> stateVisitCount = new ConcurrentHashMap<>();

    // ===================================================
    // ❌ FAILURES
    // ===================================================
    private static final Map<String, Integer> failureCount = new ConcurrentHashMap<>();
    private static final Map<String, Integer> stateFailures = new ConcurrentHashMap<>();

    // ===================================================
    // 🧠 RL MEMORY
    // ===================================================
    private static final Map<String, Double> stateRewards = new ConcurrentHashMap<>();

    // ===================================================
    // 📜 HISTORY
    // ===================================================
    private static final List<String> actionHistory = new CopyOnWriteArrayList<>();
    private static final List<String> navigationPath = new CopyOnWriteArrayList<>();

    // ===================================================
    // 🔹 STATE KEY
    // ===================================================
    private static String key(State state) {
        return state == null ? "NULL_STATE" : state.signature();
    }

    // ===================================================
    // 🔹 STATE VISIT
    // ===================================================
    public static void markStateVisited(State state) {
        stateVisitCount.merge(key(state), 1, Integer::sum);
    }

    public static int getStateVisitCount(State state) {
        return stateVisitCount.getOrDefault(key(state), 0);
    }

    public static boolean isLoopingState(State state) {
        return getStateVisitCount(state) > 2;
    }

    // ===================================================
    // 🔹 URL STATE
    // ===================================================
    public static boolean markUrlVisited(String url) {

        String n = normalize(url);

        urlVisitCount.merge(n, 1, Integer::sum);

        if (visitedUrls.add(n)) {
            navigationPath.add(n);
            return true;
        }

        return false;
    }

    public static int getUrlVisitCount(String url) {
        return urlVisitCount.getOrDefault(normalize(url), 0);
    }

    // ===================================================
    // 🔹 ELEMENT STATE
    // ===================================================
    public static boolean markElementVisited(String key) {

        String n = normalize(key);

        elementVisitCount.merge(n, 1, Integer::sum);

        return visitedElements.add(n);
    }

    public static int getElementVisitCount(String key) {
        return elementVisitCount.getOrDefault(normalize(key), 0);
    }

    // ===================================================
    // 🧠 ACTION TRACKING
    // ===================================================
    public static void recordAction(String action) {
        actionHistory.add(action);
    }

    public static int getFailureCount(String action) {
        return failureCount.getOrDefault(action, 0);
    }

    public static void recordFailure(String action, State state) {

        failureCount.merge(action, 1, Integer::sum);
        stateFailures.merge(key(state), 1, Integer::sum);
    }

    public static int getStateFailures(State state) {
        return stateFailures.getOrDefault(key(state), 0);
    }

    // ===================================================
    // 🧠 REWARD SYSTEM (SAFE)
    // ===================================================
    public static void reward(State state, double value) {

        if (state == null) return;

        // clamp step reward
        value = Math.max(-5, Math.min(5, value));

        stateRewards.merge(key(state), value, Double::sum);

        // 💣 clamp accumulated reward
        stateRewards.computeIfPresent(key(state),
                (k, v) -> Math.max(-50, Math.min(50, v)));
    }

    public static double getReward(State state) {
        return stateRewards.getOrDefault(key(state), 0.0);
    }

    // ===================================================
    // 🧠 DECISION HELPERS
    // ===================================================
    public static boolean shouldAvoidState(State state) {

        return getStateVisitCount(state) > 4
                || getStateFailures(state) > 2;
    }

    public static boolean shouldAvoidUrl(String url) {
        return getUrlVisitCount(url) > 3;
    }

    public static boolean shouldAvoidElement(String key) {
        return getElementVisitCount(key) > 3;
    }

    // ===================================================
    // 💣 DECAY (IMPORTANT)
    // ===================================================
    public static void decay() {

        stateRewards.replaceAll((k, v) -> v * 0.95);

        failureCount.replaceAll((k, v) -> Math.max(0, v - 1));

        stateVisitCount.replaceAll((k, v) -> Math.max(0, v - 1));
    }

    // ===================================================
    // 🧹 RESET
    // ===================================================
    public static void reset() {

        visitedUrls.clear();
        visitedElements.clear();
        elementVisitCount.clear();
        urlVisitCount.clear();
        stateVisitCount.clear();
        actionHistory.clear();
        navigationPath.clear();
        failureCount.clear();
        stateFailures.clear();
        stateRewards.clear();
    }

    // ===================================================
    // 🔧 UTILS
    // ===================================================
    private static String normalize(String value) {

        if (value == null) return "";

        value = value.trim().toLowerCase();

        int i = value.indexOf("#");
        if (i != -1) value = value.substring(0, i);

        i = value.indexOf("?");
        if (i != -1) value = value.substring(0, i);

        if (value.endsWith("/"))
            value = value.substring(0, value.length() - 1);

        return value;
    }
}