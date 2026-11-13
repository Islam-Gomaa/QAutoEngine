package qaengine.core.engine;

import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Unexplored candidates, shallowest first. Ties break on a seeded hash, so the same seed always
 * produces the same order and a run can be reproduced. Candidates deeper than the run's depth
 * budget are never scheduled; they are counted so the run can say it was cut short.
 */
public final class Frontier {

    private final PriorityQueue<Candidate> queue;
    private final Set<String> seen = new HashSet<>();
    private final Set<String> beyondDepth = new HashSet<>();
    private final int maxDepth;

    /** @param maxDepth deepest link distance from a start point that may be visited; starts are depth 0 */
    public Frontier(long seed, int maxDepth) {
        if (maxDepth < 0) throw new IllegalArgumentException("maxDepth must not be negative");
        Comparator<Candidate> order = Comparator.<Candidate>comparingInt(Candidate::depth)
                .thenComparingLong(c -> mix(c.url().hashCode() ^ seed))
                .thenComparing(Candidate::url);
        this.queue = new PriorityQueue<>(order);
        this.maxDepth = maxDepth;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** Returns false when this URL was already scheduled, or is beyond the depth budget. */
    public boolean add(Candidate candidate) {
        String key = key(candidate.url());
        if (candidate.depth() > maxDepth) {
            // Not marked as seen: a shorter path found later may still bring it within the budget.
            if (!seen.contains(key)) beyondDepth.add(key);
            return false;
        }
        if (!seen.add(key)) return false;
        beyondDepth.remove(key);
        queue.add(candidate);
        return true;
    }

    /** Distinct URLs that were found but left out because they were too deep. */
    public int beyondDepth() {
        return beyondDepth.size();
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
