package engine.learning;

import java.util.*;

public class LoopDetector {

    private static final Deque<String> recentStates = new ArrayDeque<>();
    private static final int MAX_HISTORY = 6;

    public static void record(String state) {

        recentStates.addLast(state);

        if (recentStates.size() > MAX_HISTORY) {
            recentStates.removeFirst();
        }
    }

    public static boolean isLooping(String state) {

        int count = 0;

        for (String s : recentStates) {
            if (s.equals(state)) count++;
        }

        return count >= 3;
    }

    public static void reset() {
        recentStates.clear();
    }
}