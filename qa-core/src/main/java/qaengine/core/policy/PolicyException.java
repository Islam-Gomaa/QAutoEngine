package qaengine.core.policy;

/** The policy configuration itself is unsafe or invalid. */
public final class PolicyException extends RuntimeException {
    public PolicyException(String message) {
        super(message);
    }
}
