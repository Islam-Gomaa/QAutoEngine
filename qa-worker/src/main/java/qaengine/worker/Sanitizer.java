package qaengine.worker;

import java.util.List;
import qaengine.contracts.WorkerContracts.ActionReport;
import qaengine.contracts.WorkerContracts.AuthReport;
import qaengine.contracts.WorkerContracts.DialogReport;
import qaengine.contracts.WorkerContracts.DiscoveredPage;
import qaengine.contracts.WorkerContracts.FieldReport;
import qaengine.contracts.WorkerContracts.FormReport;
import qaengine.contracts.WorkerContracts.NavigationReport;
import qaengine.contracts.WorkerContracts.TableReport;
import qaengine.core.discovery.PageInventory;
import qaengine.core.discovery.SafetyClassifier;
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

    /** Free text that may quote the target, such as a scripted case's summary. */
    String text(String s) {
        return redactor.redact(s);
    }

        /** A scripted case's step. Its description and note may quote the target's own words, so they are redacted. */
    StepReport scriptStep(qaengine.core.script.ScriptRunner.StepResult r, String caseTitle, String url) {
        String label = redactor.redact(caseTitle) + ": " + redactor.redact(r.step().describe());
        String note = (r.failure() == null ? "" : r.failure().name() + ": ") + redactor.redact(r.note());
        List<String> locators = r.step().target() == null ? List.of() : List.of(redactor.redact(r.step().target().describe()));
        return new StepReport(r.sequence(), 0, "SCRIPT_" + r.step().op().name(), redactor.redact(url), label, locators, r.verdict().name(), 0, "", note, r.durationMs());
    }

    /** Detail and suggested locators come from the target's page, so they are redacted like everything else. */
    AuthReport auth(AuthenticationResult r, boolean connectionOnly, int signIn) {
        String status = connectionOnly && r.failure() == null ? "CONNECTED" : r.status().name();
        java.util.Map<String, Locator> suggestions = new java.util.LinkedHashMap<>();
        r.suggestions().forEach((k, v) -> suggestions.put(k, new Locator(v.strategy().name(), redactor.redact(v.value()), v.name() == null ? null : redactor.redact(v.name()))));
        return new AuthReport(status, r.failure() == null ? null : r.failure().name(), r.stage().name(), redactor.redact(r.detail()), r.duration().toMillis(), suggestions, signIn);
    }

    /** Names, labels, headings and addresses all come from the target's page. */
    DiscoveredPage page(String fingerprint, int step, int depth, PageInventory p) {
        java.util.function.Function<String, String> r = redactor::redact;
        var actions = SafetyClassifier.classify(p.actions()).stream().map(c -> {
            var a = c.action();
            Locator locator = a.locator().isEmpty() ? null : new Locator(a.locator().get("strategy"), r.apply(a.locator().get("value")), a.locator().get("name") == null ? null : r.apply(a.locator().get("name")));
            return new ActionReport(a.kind(), a.role(), r.apply(a.name()), a.href() == null ? null : r.apply(a.href()), locator, c.safety().name(), c.reason());
        }).toList();
        return new DiscoveredPage(fingerprint, step, depth, r.apply(p.url()), r.apply(p.title()), p.headings().stream().map(r).toList(),
                p.navigation().stream().map(n -> new NavigationReport(r.apply(n.name()), n.links().stream().map(r).toList())).toList(),
                p.forms().stream().map(f -> new FormReport(r.apply(f.name()), f.method(), r.apply(f.action()),
                        f.fields().stream().map(x -> new FieldReport(r.apply(x.label()), r.apply(x.name()), x.type(), x.required(), x.labelled())).toList(), r.apply(f.submitLabel()))).toList(),
                p.tables().stream().map(t -> new TableReport(r.apply(t.caption()), t.headers().stream().map(r).toList(), t.rows())).toList(),
                p.dialogs().stream().map(d -> new DialogReport(r.apply(d.name()), d.open())).toList(), actions);
    }

    FindingReport finding(Observation o) {
        var f = o.finding();
        List<String> evidence = o.evidence().stream().filter(e -> e.status() == EvidenceRef.Status.STORED).map(EvidenceRef::sha256).toList();
        return new FindingReport(o.step(), f.fingerprint(), f.type().name(), f.severity().name(), redactor.redact(f.url()), redactor.redact(f.title()), redactor.redact(f.detail()), evidence);
    }
}
