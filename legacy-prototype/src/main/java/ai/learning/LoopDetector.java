package ai.learning;

import java.util.*;

public class LoopDetector {

    private static final Deque<String> history = new ArrayDeque<>();
    private static final int MAX_HISTORY = 10;

    // ===================================================
    // 🧠 RECORD STATE
    // ===================================================
    public static void record(State state) {

        if (state == null) return;

        String sig = state.signature(); // 💣 use State logic

        history.addLast(sig);

        if (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }

    // ===================================================
    // 🔁 LOOP DETECTION (SMART)
    // ===================================================
    public static boolean isLooping(State state) {

        if (state == null) return false;

        String sig = state.signature(); // 💣 consistent

        int count = 0;

        for (String s : history) {
            if (s.equals(sig)) count++;
        }

        return count >= 3;
    }

    // ===================================================
    // 🔥 STRONG LOOP (pattern detection)
    // ===================================================
    public static boolean isStrongLoop() {

        if (history.size() < 4) return false;

        List<String> list = new ArrayList<>(history);
        int n = list.size();

        // ABAB pattern
        if (n >= 4) {
            String a = list.get(n - 1);
            String b = list.get(n - 2);
            String c = list.get(n - 3);
            String d = list.get(n - 4);

            if (a.equals(c) && b.equals(d)) {
                return true;
            }
        }

        // AAA pattern
        if (n >= 3) {
            String a = list.get(n - 1);
            String b = list.get(n - 2);
            String c = list.get(n - 3);

            if (a.equals(b) && b.equals(c)) {
                return true;
            }
        }

        return false;
    }

    // ===================================================
    // 🔥 LOOP PENALTY
    // ===================================================
    public static double loopPenalty(State state) {

        if (state == null) return 0;

        if (isStrongLoop()) return -5;
        if (isLooping(state)) return -2;

        return 0;
    }

    // ===================================================
    // 🔥 STUCK DETECTION
    // ===================================================
    public static boolean isStuck() {

        if (history.size() < 5) return false;

        Set<String> unique = new HashSet<>(history);

        return unique.size() <= 2;
    }

    // ===================================================
    public static void reset() {
        history.clear();
    }
}