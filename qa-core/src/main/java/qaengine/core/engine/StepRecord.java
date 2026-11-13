package qaengine.core.engine;

import java.util.List;
import qaengine.core.policy.ActionRequest;
import qaengine.core.verdict.Verdict;

/** One action as it happened, with what is needed to repeat it. */
public record StepRecord(int sequence, int parent, ActionRequest.Kind kind, String url, String label,
                         List<String> locators, Verdict verdict, int status, String state, String note, long durationMs) {
    public StepRecord {
        locators = List.copyOf(locators);
    }

    public StepRecord(int sequence, int parent, ActionRequest.Kind kind, String url, String label,
                      List<String> locators, Verdict verdict, int status, String state, String note) {
        this(sequence, parent, kind, url, label, locators, verdict, status, state, note, 0);
    }
}
