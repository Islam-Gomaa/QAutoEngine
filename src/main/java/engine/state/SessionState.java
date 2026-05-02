package engine.state;

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
    // 🧠 STATE TRACKING (🔥 NEW)
    // ===================================================
    private static final Map<String, Integer> stateVisitCount = new ConcurrentHashMap<>();

    // ===================================================
    // 🧠 CONTEXT
    // ===================================================
    private static volatile String currentUrl = "";
    private static volatile String lastAction = "";

    // ===================================================
    // 📜 HISTORY
    // ===================================================
    private static final List<String> actionHistory = new CopyOnWriteArrayList<>();
    private static final List<String> navigationPath = new CopyOnWriteArrayList<>();

    // ===================================================
    // ❌ FAILURES (UPGRADED)
    // ===================================================
    private static final Map<String, Integer> failureCount = new ConcurrentHashMap<>();
    private static final Map<String, Integer> stateFailures = new ConcurrentHashMap<>();

    // ===================================================
    // 📊 STATS
    // ===================================================
    private static final Map<String, Integer> actionStats = new ConcurrentHashMap<>();

    // ===================================================
    // 🧠 RL MEMORY
    // ===================================================
    private static final Map<String, Double> stateRewards = new ConcurrentHashMap<>();

    // ===================================================
    // 🔹 STATE VISIT
    // ===================================================
    public static void markStateVisited(String state) {
        stateVisitCount.merge(state, 1, Integer::sum);
    }

    public static int getStateVisitCount(String state) {
        return stateVisitCount.getOrDefault(state, 0);
    }

    public static boolean isRevisiting(String state) {
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
            currentUrl = n;
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

        lastAction = action;
        actionHistory.add(action);

        actionStats.merge(action, 1, Integer::sum);
    }

    public static int getFailureCount(String action) {
        return failureCount.getOrDefault(action, 0);
    }

    public static void recordFailure(String action, String state) {

        failureCount.merge(action, 1, Integer::sum);
        stateFailures.merge(state, 1, Integer::sum);
    }

    public static int getStateFailures(String state) {
        return stateFailures.getOrDefault(state, 0);
    }

    // ===================================================
    // 🧠 INTELLIGENCE
    // ===================================================
    public static boolean isStuck() {

        if (actionHistory.size() < 6) return false;

        List<String> last = actionHistory.subList(
                actionHistory.size() - 5,
                actionHistory.size()
        );

        return new HashSet<>(last).size() <= 2;
    }

    public static boolean isLooping() {

        if (navigationPath.size() < 4) return false;

        String last = navigationPath.get(navigationPath.size() - 1);

        return Collections.frequency(navigationPath, last) > 2;
    }

    // ===================================================
    // 🧠 REWARD SYSTEM (UPGRADED)
    // ===================================================
    public static void reward(String state, double value) {
        stateRewards.merge(state, value, Double::sum);
    }

    public static double getReward(String state) {
        return stateRewards.getOrDefault(state, 0.0);
    }

    // ===================================================
    // 💣 DECAY (IMPORTANT)
    // ===================================================
    public static void decay() {

        for (String key : stateRewards.keySet()) {
            stateRewards.put(key, stateRewards.get(key) * 0.95);
        }
    }

    // ===================================================
    // 🧠 HELPERS
    // ===================================================
    public static boolean shouldAvoidUrl(String url) {

        String n = normalize(url);

        return getUrlVisitCount(n) > 2 || isLooping();
    }

    public static boolean shouldAvoidElement(String key) {

        return getElementVisitCount(key) > 3;
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
        actionStats.clear();
        stateRewards.clear();

        currentUrl = "";
        lastAction = "";
    }

    // ===================================================
    private static String normalize(String value) {

        if (value == null) return "";

        try {
            value = value.trim().toLowerCase();

            int hashIndex = value.indexOf("#");
            if (hashIndex != -1) value = value.substring(0, hashIndex);

            int queryIndex = value.indexOf("?");
            if (queryIndex != -1) value = value.substring(0, queryIndex);

            if (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }

        } catch (Exception ignored) {}

        return value;
    }
}