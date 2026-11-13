package qaengine.core.policy;

/** A policy outcome with its reason, so every block can be explained and audited. */
public record Decision(boolean allowed, Reason reason, String detail) {

    public enum Reason {
        OUT_OF_SCOPE, HARD_DENY, ACTION_NOT_ALLOWED, UNSAFE_METHOD, EXCEPTION_MATCHED,
        SAFE_READ, ALLOWED_INTERACTION, BUDGET_EXCEEDED, AUTH_WINDOW_CLOSED, POLICY_ERROR
    }

    public static Decision allow(Reason reason, String detail) {
        return new Decision(true, reason, detail);
    }

    public static Decision block(Reason reason, String detail) {
        return new Decision(false, reason, detail);
    }
}
