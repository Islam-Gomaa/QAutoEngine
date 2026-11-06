package qaengine.core.policy;

/** How much a run may do. Anything not explicitly allowed by the mode is blocked. */
public enum ExecutionMode {
    /** Observes and navigates. State-changing requests are blocked. */
    READ_ONLY,
    /** Fills forms to check client-side validation. Nothing is submitted. */
    VALIDATION_ONLY,
    /** Scoped, expiring, approved mutation. Refused until the approval workflow exists. */
    AUTHORIZED_MUTATION
}
