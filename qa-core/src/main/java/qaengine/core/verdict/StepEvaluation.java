package qaengine.core.verdict;

import java.util.List;

public record StepEvaluation(Verdict verdict, List<Finding> findings, String note) {
    public StepEvaluation {
        findings = List.copyOf(findings);
    }
}
