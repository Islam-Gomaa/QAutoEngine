package qaengine.core.engine;

import java.util.List;
import qaengine.core.evidence.EvidenceRef;
import qaengine.core.verdict.Finding;

/** A confirmed finding, the step that exposed it, and its proof. */
public record Observation(Finding finding, int step, List<EvidenceRef> evidence) {
    public Observation {
        evidence = List.copyOf(evidence);
    }
}
