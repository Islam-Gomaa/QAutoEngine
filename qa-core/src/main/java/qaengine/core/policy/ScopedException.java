package qaengine.core.policy;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A narrow, expiring permission for one exact request shape. Exceptions never override hard deny
 * rules and never enable mutation.
 *
 * @param graphqlOperation when set, the body must be exactly this one named query
 */
public record ScopedException(String id, Purpose purpose, String origin, String method, String pathTemplate,
                              String graphqlOperation, Instant expires, int maxUses, String approvedBy,
                              ApproverRole approverRole) {

    public enum Purpose { AUTH, SESSION, READ_OPERATION }

    public enum ApproverRole { PROJECT_ADMIN, ORGANIZATION_ADMIN }

    public ScopedException {
        if (origin.contains("*") || pathTemplate.contains("*")) throw new PolicyException("Wildcards are not allowed in exceptions");
        if (expires == null) throw new PolicyException("An exception needs an expiry");
        if (maxUses <= 0) throw new PolicyException("An exception needs a use limit");
        if (approvedBy == null || approvedBy.isBlank()) throw new PolicyException("An exception needs an approver");
        origin = TargetScope.originOf(origin).orElseThrow(() -> new PolicyException("Invalid exception origin"));
        method = method.toUpperCase(Locale.ROOT);
    }

    boolean matches(String requestOrigin, String requestMethod, String path, String body) {
        if (!origin.equals(requestOrigin) || !method.equals(requestMethod) || !pathMatches(path)) return false;
        if (graphqlOperation == null) return true;
        return GraphQlInspector.singleNamedQuery(body).filter(graphqlOperation::equals).isPresent();
    }

    private boolean pathMatches(String path) {
        String regex = Pattern.quote(pathTemplate).replaceAll("\\{[^}/]+\\}", "\\\\E[^/]+\\\\Q");
        return path != null && path.matches(regex);
    }
}
