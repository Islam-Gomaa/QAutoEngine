package qaengine.core.engine;

import java.util.ArrayList;
import java.util.List;
import qaengine.core.model.Navigation;
import qaengine.core.model.Signal;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.ActionRequest;
import qaengine.core.policy.Decision;
import qaengine.core.port.BrowserPort;
import qaengine.core.verdict.ExpectedOutcome;
import qaengine.core.verdict.Finding;
import qaengine.core.verdict.StepEvaluation;
import qaengine.core.verdict.VerdictEngine;

/** Repeats recorded steps through the same gate and verdict rules. */
public final class Replayer {

    private final ActionGate gate;
    private final BrowserPort browser;
    private final VerdictEngine verdicts;

    public Replayer(ActionGate gate, BrowserPort browser, VerdictEngine verdicts) {
        this.gate = gate;
        this.browser = browser;
        this.verdicts = verdicts;
    }

    public List<Finding> replay(List<StepRecord> path) {
        List<Finding> findings = new ArrayList<>();
        for (StepRecord step : path) {
            Decision d = gate.decideAction(new ActionRequest(ActionRequest.Kind.NAVIGATE_LINK, step.url(), java.util.Map.of()));
            if (!d.allowed()) continue;
            browser.drainSignals();
            Navigation nav = browser.open(step.url());
            List<Signal> signals = browser.drainSignals();
            StepEvaluation eval = verdicts.evaluate(nav, ExpectedOutcome.PAGE_LOADS, true, signals);
            findings.addAll(eval.findings());
        }
        return findings;
    }
}
