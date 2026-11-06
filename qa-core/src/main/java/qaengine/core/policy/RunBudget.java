package qaengine.core.policy;

import java.time.Duration;

/** Hard limits for one run. A run always terminates. */
public record RunBudget(int maxActions, int maxPages, int maxDepth, Duration maxDuration, int maxLoginAttempts) {

    public RunBudget {
        if (maxActions <= 0 || maxPages <= 0 || maxDepth <= 0 || maxLoginAttempts <= 0 || maxDuration.isNegative() || maxDuration.isZero()) {
            throw new PolicyException("Budgets must be positive");
        }
    }

    public static RunBudget defaults() {
        return new RunBudget(500, 200, 10, Duration.ofHours(2), 2);
    }

    public static RunBudget production() {
        return new RunBudget(100, 50, 5, Duration.ofMinutes(30), 1);
    }
}
