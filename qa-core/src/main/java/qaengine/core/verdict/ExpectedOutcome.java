package qaengine.core.verdict;

/** What an action must achieve. Declaring it up front is what stops a no-op from passing. */
public enum ExpectedOutcome {
    /** The target document loads successfully. */
    PAGE_LOADS,
    /** The page structure changes in place. */
    STATE_CHANGES
}
