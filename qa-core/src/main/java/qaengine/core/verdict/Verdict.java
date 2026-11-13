package qaengine.core.verdict;

/** The outcome of a step. A change on the page is never, by itself, a pass. */
public enum Verdict {
    PASS,
    FAIL,
    /** The evidence does not support a pass or a failure. */
    INCONCLUSIVE,
    /** Policy stopped the action. Counts against coverage, not as a defect. */
    BLOCKED
}
