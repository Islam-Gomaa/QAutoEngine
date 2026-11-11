package qaengine.worker;

import java.util.List;
import qaengine.contracts.WorkerContracts.AuthReport;
import qaengine.contracts.WorkerContracts.FindingReport;
import qaengine.contracts.WorkerContracts.Locator;
import qaengine.core.auth.AuthenticationResult;
import qaengine.contracts.WorkerContracts.StepReport;
import qaengine.core.engine.Observation;
import qaengine.core.engine.StepRecord;
import qaengine.core.evidence.EvidenceRef;
import qaengine.core.evidence.Redactor;

/**
 * Everything the worker reports passes through here. URLs, labels and messages all come from the
 * target, and a crawled link can carry a token in its query string.
 */
final class Sanitizer {

    private final Redactor redactor;

    Sanitizer(Redactor redactor) {
        this.redactor = redactor;
    }

    StepReport step(StepRecord s) {
        return new StepReport(s.sequence(), s.parent(), s.kind().name(), redactor.redact(s.url()), redactor.redact(s.label()),
                s.locators().stream().map(redactor::redact).toList(), s.verdict().name(), s.status(), s.state(), redactor.redact(s.note()), s.durationMs());
    }

    /** Detail and suggested locators come from the target's page, so they are redacted like everything else. */
    AuthReport auth(AuthenticationResult r, boolean connectionOnly, int signIn) {
        String status = connectionOnly && r.failure() == null ? "CONNECTED" : r.status().name();
        java.util.Map<String, Locator> suggestions = new java.util.LinkedHashMap<>();
        r.suggestions().forEach((k, v) -> suggestions.put(k, new Locator(v.strategy().name(), redactor.redact(v.value()), v.name() == null ? null : redactor.redact(v.name()))));
        return new AuthReport(status, r.failure() == null ? null : r.failure().name(), r.stage().name(), redactor.redact(r.detail()), r.duration().toMillis(), suggestions, signIn);
    }

    FindingReport finding(Observation o) {
        var f = o.finding();
        List<String> evidence = o.evidence().stream().filter(e -> e.status() == EvidenceRef.Status.STORED).map(EvidenceRef::sha256).toList();
        return new FindingReport(o.step(), f.fingerprint(), f.type().name(), f.severity().name(), redactor.redact(f.url()), redactor.redact(f.title()), redactor.redact(f.detail()), evidence);
    }
}
