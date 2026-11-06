package qaengine.core.policy;

import java.util.List;
import java.util.Set;

/** Validated rules for one run. Unsafe combinations cannot be constructed. */
public record ExecutionPolicy(ExecutionMode mode, EnvironmentClass environment, TargetScope scope,
                              List<ScopedException> exceptions, Set<String> extraRiskTokens, RunBudget budget) {

    public ExecutionPolicy {
        exceptions = List.copyOf(exceptions);
        extraRiskTokens = Set.copyOf(extraRiskTokens);
        if (mode == ExecutionMode.AUTHORIZED_MUTATION) {
            throw new PolicyException("AUTHORIZED_MUTATION needs an approval record, which does not exist yet");
        }
        if (environment == EnvironmentClass.PRODUCTION) {
            if (mode != ExecutionMode.READ_ONLY) throw new PolicyException("Production is READ_ONLY only");
            if (scope.includePaths().isEmpty()) throw new PolicyException("Production needs explicit include paths");
            for (ScopedException e : exceptions) {
                if (e.approverRole() != ScopedException.ApproverRole.ORGANIZATION_ADMIN) {
                    throw new PolicyException("Production exceptions need Organization Admin approval: " + e.id());
                }
            }
        }
    }

    public static ExecutionPolicy readOnly(EnvironmentClass environment, TargetScope scope) {
        return new ExecutionPolicy(ExecutionMode.READ_ONLY, environment, scope, List.of(), Set.of(),
                environment == EnvironmentClass.PRODUCTION ? RunBudget.production() : RunBudget.defaults());
    }
}
