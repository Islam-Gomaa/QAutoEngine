package engine.state;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SessionState {

    // ===================================================
    // 🌐 VISITED
    // ===================================================
    private static final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private static final Set<String> visitedElements = ConcurrentHashMap.newKeySet();

    // 🔥 ADVANCED ELEMENT TRACKING
    private static final Map<String, Integer> elementVisitCount =
            new ConcurrentHashMap<>();

    // ===================================================
    // 🧠 CONTEXT
    // ===================================================
    private static volatile String currentUrl = "";
    private static volatile String lastAction = "";

    // ===================================================
    // 📜 HISTORY
    // ===================================================
    private static final List<String> actionHistory =
            Collections.synchronizedList(new ArrayList<>());

    private static final List<String> navigationPath =
            Collections.synchronizedList(new ArrayList<>());

    // ===================================================
    // ❌ FAILURES
    // ===================================================
    private static final List<String> failures =
            Collections.synchronizedList(new ArrayList<>());

    // ===================================================
    // 📊 STATS
    // ===================================================
    private static final Map<String, Integer> actionStats =
            new ConcurrentHashMap<>();

    // ===================================================
    // 🔹 URL STATE
    // ===================================================
    public static boolean isUrlVisited(String url) {
        return visitedUrls.contains(normalize(url));
    }

    public static boolean markUrlVisited(String url) {

        String n = normalize(url);

        if (visitedUrls.add(n)) {
            navigationPath.add(n);
            currentUrl = n;
            return true;
        }

        return false;
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

        // 🔥 track frequency
        elementVisitCount.merge(n, 1, Integer::sum);

        return visitedElements.add(n);
    }

    // 🔢 ELEMENT COUNT (🔥 FIX)
    public static int elementCount() {
        return visitedElements.size();
    }

    public static boolean hasExploredEnoughElements(int threshold) {
        return visitedElements.size() >= threshold;
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

    public static Map<String, Integer> getActionStats() {
        return new HashMap<>(actionStats);
    }

    public static int getActionCount(String action) {
        return actionStats.getOrDefault(action, 0);
    }

    // ===================================================
    // ❌ FAILURES
    // ===================================================
    public static void recordFailure(String action) {
        failures.add(action);
    }

    public static List<String> getFailures() {
        return new ArrayList<>(failures);
    }

    public static int getFailureCount(String action) {

        int count = 0;

        for (String f : failures) {
            if (f.equals(action)) count++;
        }

        return count;
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
    // 🧠 INTELLIGENCE
    // ===================================================
    public static boolean isStuck() {
        return actionHistory.size() > 10 &&
                new HashSet<>(actionHistory.subList(
                        Math.max(0, actionHistory.size() - 5),
                        actionHistory.size()
                )).size() <= 2;
    }

    // ===================================================
    // 🔄 RESET
    // ===================================================
    public static void reset() {
        visitedUrls.clear();
        visitedElements.clear();
        elementVisitCount.clear();
        actionHistory.clear();
        navigationPath.clear();
        failures.clear();
        actionStats.clear();
        currentUrl = "";
        lastAction = "";
    }

    // ===================================================
    // 🧹 NORMALIZE
    // ===================================================
    private static String normalize(String value) {

        if (value == null) return "";

        try {
            value = value.split("#")[0];
            value = value.split("\\?")[0];

            if (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }

        } catch (Exception ignored) {}

        return value.trim().toLowerCase();
    }
}