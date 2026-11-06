package qaengine.core.engine;

import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Unexplored candidates, shallowest first. Ties break on a seeded hash, so the same seed always
 * produces the same order and a run can be reproduced.
 */
public final class Frontier {

    private final PriorityQueue<Candidate> queue;
    private final Set<String> seen = new HashSet<>();

    public Frontier(long seed) {
        Comparator<Candidate> order = Comparator.<Candidate>comparingInt(Candidate::depth)
                .thenComparingLong(c -> mix(c.url().hashCode() ^ seed))
                .thenComparing(Candidate::url);
        this.queue = new PriorityQueue<>(order);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** Returns false when this URL was already scheduled. */
    public boolean add(Candidate candidate) {
        if (!seen.add(key(candidate.url()))) return false;
        queue.add(candidate);
        return true;
    }

    public Candidate next() {
        return queue.poll();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    static String key(String url) {
        String u = url.replaceAll("#.*$", "");
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
