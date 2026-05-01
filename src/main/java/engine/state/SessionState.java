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

    // 🔥 frequency tracking
    private static final Map<String, Integer> elementVisitCount =
            new ConcurrentHashMap<>();

    private static final Map<String, Integer> urlVisitCount =
            new ConcurrentHashMap<>();

    // ===================================================
    // 🧠 CONTEXT
    // ===================================================
    private static volatile String currentUrl = "";
    private static volatile String lastAction = "";

    // ===================================================
    // 📜 HISTORY (🔥 thread-safe upgrade)
    // ===================================================
    private static final List<String> actionHistory = new CopyOnWriteArrayList<>();
    private static final List<String> navigationPath = new CopyOnWriteArrayList<>();

    // ===================================================
    // ❌ FAILURES (🔥 optimized)
    // ===================================================
    private static final Map<String, Integer> failureCount =
            new ConcurrentHashMap<>();

    // ===================================================
    // 📊 STATS
    // ===================================================
    private static final Map<String, Integer> actionStats =
            new ConcurrentHashMap<>();

    // ===================================================
    // 🧠 RL MEMORY
    // ===================================================
    private static final Map<String, Double> stateRewards =
            new ConcurrentHashMap<>();

    // ===================================================
    // 🔹 URL STATE
    // ===================================================
    public static boolean isUrlVisited(String url) {
        return visitedUrls.contains(normalize(url));
    }

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

    public static int urlCount() {
        return visitedUrls.size();
    }

    // ===================================================
    // 🔹 ELEMENT STATE
    // ===================================================
    public static boolean isElementVisited(String key) {
        return visitedElements.contains(normalize(key));
    }

    public static boolean markElementVisited(String key) {

        String n = normalize(key);

        elementVisitCount.merge(n, 1, Integer::sum);

        return visitedElements.add(n);
    }

    public static int elementCount() {
        return visitedElements.size();
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

    public static String getLastAction() {
        return lastAction;
    }

    public static List<String> getActionHistory() {
        return new ArrayList<>(actionHistory);
    }

    public static int getActionCount(String action) {
        return actionStats.getOrDefault(action, 0);
    }

    // 🔥 IMPORTANT FIX (علشان LearningEngine)
    public static Map<String, Integer> getActionStats() {
        return new HashMap<>(actionStats);
    }

    // ===================================================
    // ❌ FAILURES (🔥 FIXED PERFORMANCE)
    // ===================================================
    public static void recordFailure(String action) {
        failureCount.merge(action, 1, Integer::sum);
    }

    public static int getFailureCount(String action) {
        return failureCount.getOrDefault(action, 0);
    }

    // ===================================================
    // 🧭 NAVIGATION PATH
    // ===================================================
    public static List<String> getNavigationPath() {
        return new ArrayList<>(navigationPath);
    }

    public static String getCurrentUrl() {
        return currentUrl;
    }

    // ===================================================
    // 🧠 AI INTELLIGENCE
    // ===================================================
    public static boolean isStuck() {

        if (actionHistory.size() < 6) return false;

        List<String> last =
                actionHistory.subList(
                        actionHistory.size() - 5,
                        actionHistory.size()
                );

        Set<String> unique = new HashSet<>(last);

        return unique.size() <= 2;
    }

    // 🔥 anti-loop detection
    public static boolean isLooping() {

        if (navigationPath.size() < 4) return false;

        String last = navigationPath.get(navigationPath.size() - 1);

        return Collections.frequency(navigationPath, last) > 2;
    }

    // ===================================================
    // 🧠 RL REWARD SYSTEM
    // ===================================================
    public static void reward(String stateKey, double value) {
        stateRewards.merge(stateKey, value, Double::sum);
    }

    public static double getReward(String stateKey) {
        return stateRewards.getOrDefault(stateKey, 0.0);
    }

    // ===================================================
    // 🧠 SMART DECISIONS HELPERS
    // ===================================================
    public static boolean shouldAvoidUrl(String url) {

        String n = normalize(url);

        if (getUrlVisitCount(n) > 2) return true;

        if (isLooping()) return true;

        return false;
    }

    public static boolean shouldAvoidElement(String key) {

        int visits = getElementVisitCount(key);

        return visits > 3;
    }

    // ===================================================
    // 🔄 RESET
    // ===================================================
    public static void reset() {

        visitedUrls.clear();
        visitedElements.clear();
        elementVisitCount.clear();
        urlVisitCount.clear();
        actionHistory.clear();
        navigationPath.clear();
        failureCount.clear();
        actionStats.clear();
        stateRewards.clear();

        currentUrl = "";
        lastAction = "";
    }

    // ===================================================
    // 🧹 NORMALIZE (🔥 improved)
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

    // ===================================================
    // 📊 VISITED COUNT (NEW 🔥)
    // ===================================================
    public static int getVisitedCount() {
        return visitedUrls.size(); // أو أي collection عندك
    }
}