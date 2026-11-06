package qaengine.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import qaengine.core.engine.Observation;
import qaengine.core.engine.StepRecord;
import qaengine.core.evidence.EvidenceRef;
import qaengine.core.evidence.EvidenceType;
import qaengine.core.evidence.Redactor;
import qaengine.core.policy.ActionRequest;
import qaengine.core.verdict.Finding;
import qaengine.core.verdict.Severity;
import qaengine.core.verdict.Verdict;

class SanitizerTest {

    static final String CANARY = "known-secret-canary-55";
    final Sanitizer sanitizer = new Sanitizer(new Redactor(List.of(CANARY)));

    @Test
    void stepsCarryNoSecrets() {
        var step = new StepRecord(1, 0, ActionRequest.Kind.NAVIGATE_LINK, "https://t/a?token=abc123xyz&page=2", "Hello " + CANARY,
                List.of("a[href=\"/a?token=abc123xyz\"]"), Verdict.PASS, 200, "s", "note password=hunter2xyz", 5);
        String text = sanitizer.step(step).toString();
        for (String secret : List.of("abc123xyz", CANARY, "hunter2xyz")) assertFalse(text.contains(secret), text);
        assertTrue(text.contains("page=2"), "ordinary query values stay");
    }

    @Test
    void findingsCarryNoSecretsAndKeepTheirFingerprint() {
        var finding = Finding.of(Finding.Type.CONSOLE_ERROR, Severity.LOW, "https://t/a?session=abc123xyz", "Console error", "failed for " + CANARY);
        var ref = new EvidenceRef(EvidenceType.SIGNAL_LOG, 1, "aa", 1, "x", EvidenceRef.Status.STORED);
        var dropped = EvidenceRef.dropped(EvidenceType.SCREENSHOT, 1, EvidenceRef.Status.TOO_LARGE);
        var report = sanitizer.finding(new Observation(finding, 1, List.of(ref, dropped)));
        assertFalse(report.toString().contains(CANARY));
        assertFalse(report.toString().contains("abc123xyz"));
        assertEquals(finding.fingerprint(), report.fingerprint());
        assertEquals(List.of("aa"), report.evidenceSha256());
    }
}
