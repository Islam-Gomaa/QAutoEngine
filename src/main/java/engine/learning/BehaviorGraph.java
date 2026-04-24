package engine.learning;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BehaviorGraph {

    // fromUrl → (action → toUrl)
    private static final Map<String, Map<String, String>> graph =
            new ConcurrentHashMap<>();

    // ===================================================
    // 🔗 RECORD TRANSITION
    // ===================================================
    public static void record(String fromUrl, String action, String toUrl) {

        if (fromUrl == null || action == null || toUrl == null) return;

        graph
                .computeIfAbsent(fromUrl, k -> new ConcurrentHashMap<>())
                .put(action, toUrl);

        System.out.println("📌 " + fromUrl + " --[" + action + "]--> " + toUrl);
    }

    // ===================================================
    // 🧠 SUGGEST (using BehaviorStore 🔥)
    // ===================================================
    public static Optional<String> suggest(String currentUrl) {

        Map<String, String> actions = graph.get(currentUrl);

        if (actions == null || actions.isEmpty()) {
            return Optional.empty();
        }

        return actions.keySet().stream()
                .max(Comparator.comparingDouble(BehaviorStore::getScore));
    }
}