package qaengine.api;

import java.util.Map;

/** One generated request and what it is meant to prove. */
public record ApiScenario(Kind kind, ApiOperation operation, Map<String, String> pathValues, Map<String, String> queryValues, Credential credential) {

    public enum Kind {
        POSITIVE, INVALID_TYPE, MISSING_REQUIRED, BELOW_MINIMUM, ABOVE_MAXIMUM, AT_MINIMUM, AT_MAXIMUM, INVALID_ENUM,
        NO_CREDENTIAL, INVALID_CREDENTIAL, LOW_PRIVILEGE, OPTIONS_PROBE, HEAD_PARITY, UNDOCUMENTED;

        boolean expectsRejection() {
            return this == INVALID_TYPE || this == MISSING_REQUIRED || this == BELOW_MINIMUM || this == ABOVE_MAXIMUM || this == INVALID_ENUM;
        }
    }

    public enum Credential { NONE, PRIMARY, LOW_PRIVILEGE, INVALID }

    public ApiScenario {
        pathValues = Map.copyOf(pathValues);
        queryValues = Map.copyOf(queryValues);
    }

    public String method() {
        return switch (kind) {
            case OPTIONS_PROBE -> "OPTIONS";
            case HEAD_PARITY -> "HEAD";
            default -> operation.method();
        };
    }

    public String label() {
        return kind + " " + method() + " " + operation.path();
    }
}
