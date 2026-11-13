package qaengine.core.discovery;

/**
 * What using an action would do, as far as can be told without using it. Documentation only: the gate
 * decides what may run, and in READ_ONLY nothing but navigation and in-place disclosure ever does.
 */
public enum SafetyClass {
    /** Viewing, navigating, opening a tab or filter. */
    READ_ONLY,
    /** Changes something that can be changed back, such as editing a draft. */
    REVERSIBLE,
    /** Has effects beyond the page: sending, paying, inviting, publishing, signing out. Also anything whose intent is unknown. */
    SENSITIVE,
    /** Removes or ends something: deleting, revoking, archiving. */
    DESTRUCTIVE
}
