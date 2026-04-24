package engine.distribution;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DistributionManager {

    private static final Queue<String> urlQueue = new ConcurrentLinkedQueue<>();

    // ===================================================
    // ➕ ADD URLS
    // ===================================================
    public static void add(String url) {
        if (url != null && !url.isEmpty()) {
            urlQueue.add(url);
        }
    }

    public static void addAll(Iterable<String> urls) {
        for (String url : urls) {
            add(url);
        }
    }

    // ===================================================
    // 🔄 GET NEXT
    // ===================================================
    public static String next() {
        return urlQueue.poll();
    }

    public static boolean hasNext() {
        return !urlQueue.isEmpty();
    }

    // ===================================================
    // 🧹 RESET
    // ===================================================
    public static void reset() {
        urlQueue.clear();
    }
}