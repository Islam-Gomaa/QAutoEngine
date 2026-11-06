package qaengine.core.engine;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.EvidenceRef;
import qaengine.core.evidence.EvidenceType;
import qaengine.core.model.Control;
import qaengine.core.model.Navigation;
import qaengine.core.model.PageSnapshot;
import qaengine.core.model.Signal;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.ActionRequest;
import qaengine.core.policy.Decision;
import qaengine.core.port.BrowserPort;
import qaengine.core.verdict.ExpectedOutcome;
import qaengine.core.verdict.Finding;
import qaengine.core.verdict.StepEvaluation;
import qaengine.core.verdict.Verdict;
import qaengine.core.verdict.VerdictEngine;

/**
 * Observe, propose, gate, act, verify, record. Runs until nothing is left to explore or a budget
 * ends the run. All state belongs to this object, so runs cannot leak into each other.
 */
public final class ExplorationEngine {

    private static final int MAX_DISCLOSURES_PER_STATE = 10;
    private static final List<String> DEFAULT_MASKS = List.of("input[type=password]");

    private final ActionGate gate;
    private final BrowserPort browser;
    private final VerdictEngine verdicts;
    private final EvidenceCollector evidence;
    private final Frontier frontier;
    private final Clock clock;
    private final List<String> masks;

    private final List<StepRecord> steps = new ArrayList<>();
    private final Map<String, Observation> observations = new LinkedHashMap<>();
    private final Map<String, String> states = new LinkedHashMap<>();
    private final Set<RunResult.Transition> transitions = new java.util.LinkedHashSet<>();
    private final Set<String> reportedBlocks = new HashSet<>();
    private int sequence;
    private int pages;
    private java.util.function.BooleanSupplier stopRequested = () -> false;
    private String loginPath;
    private boolean sessionLost;
    private java.util.function.Consumer<StepRecord> stepListener = s -> { };
    private java.util.function.Consumer<Observation> findingListener = o -> { };

    public ExplorationEngine(ActionGate gate, BrowserPort browser, VerdictEngine verdicts, EvidenceCollector evidence,
                             long seed, Clock clock, List<String> extraMasks) {
        this.gate = gate;
        this.browser = browser;
        this.verdicts = verdicts;
        this.evidence = evidence;
        this.frontier = new Frontier(seed);
        this.clock = clock;
        List<String> all = new ArrayList<>(DEFAULT_MASKS);
        all.addAll(extraMasks);
        this.masks = List.copyOf(all);
    }

    /** Checked before every action, which is what makes cancellation take effect between actions. */
    public ExplorationEngine stopWhen(java.util.function.BooleanSupplier stop) {
        this.stopRequested = stop;
        return this;
    }

    /** A redirect to this path during a run means the session ended, so the run stops honestly. */
    public ExplorationEngine sessionEndsAt(String path) {
        this.loginPath = path;
        return this;
    }

    /** Extra starting points, such as pages only reachable after login. */
    public ExplorationEngine seed(String url) {
        frontier.add(new Candidate(url, 0, null, 0));
        return this;
    }

    /** Receives each step and confirmed finding as it happens, so progress is visible during a run. */
    public ExplorationEngine onProgress(java.util.function.Consumer<StepRecord> steps, java.util.function.Consumer<Observation> findings) {
        this.stepListener = steps;
        this.findingListener = findings;
        return this;
    }

    public RunResult explore(String startUrl) {
        Instant deadline = clock.instant().plus(gate.policy().budget().maxDuration());
        frontier.add(new Candidate(startUrl, 0, null, 0));
        RunResult.StopReason stop = RunResult.StopReason.EXHAUSTED;
        while (!frontier.isEmpty()) {
            if (stopRequested.getAsBoolean()) { stop = RunResult.StopReason.CANCELLED; break; }
            if (sessionLost) { stop = RunResult.StopReason.SESSION_EXPIRED; break; }
            if (clock.instant().isAfter(deadline)) { stop = RunResult.StopReason.DEADLINE; break; }
            if (pages >= gate.policy().budget().maxPages()) { stop = RunResult.StopReason.BUDGET; break; }
            visit(frontier.next());
        }
        // The queue can empty on the very step that lost the session. That must not read as a finished crawl.
        if (sessionLost) stop = RunResult.StopReason.SESSION_EXPIRED;
        return new RunResult(steps, new ArrayList<>(observations.values()), states, new ArrayList<>(transitions), stop);
    }

    private void visit(Candidate c) {
        Control via = c.via();
        ActionRequest action = via != null ? via.toAction() : new ActionRequest(ActionRequest.Kind.NAVIGATE_LINK, c.url(), Map.of());
        Decision decision = gate.decideAction(action);
        if (!decision.allowed()) {
            record(c, via, Verdict.BLOCKED, 0, "", decision.reason() + ": " + decision.detail());
            return;
        }

        browser.drainSignals();
        Navigation nav = browser.open(c.url());
        pages++;
        List<Signal> signals = browser.drainSignals();
        StepEvaluation eval = verdicts.evaluate(nav, ExpectedOutcome.PAGE_LOADS, true, signals);

        if (nav.outcome() == Navigation.Outcome.REDIRECTED) {
            if (loginPath != null && nav.redirectTarget().contains(loginPath)) {
                record(c, via, Verdict.INCONCLUSIVE, 0, "", "session ended");
                sessionLost = true;
                return;
            }
            int seq = record(c, via, eval.verdict(), 0, "", eval.note());
            frontier.add(new Candidate(nav.redirectTarget(), c.depth(), null, seq));
            return;
        }

        boolean loaded = nav.outcome() == Navigation.Outcome.LOADED && nav.status() < 400;
        PageSnapshot snapshot = nav.outcome() == Navigation.Outcome.LOADED ? browser.snapshot() : null;
        String state = snapshot == null ? "" : snapshot.fingerprint();

        if (eval.verdict() == Verdict.FAIL) eval = confirm(c, nav, eval);
        int seq = record(c, via, eval.verdict(), nav.status(), state, eval.note());
        if (eval.verdict() == Verdict.FAIL) capture(seq, eval.findings(), signals);

        if (!loaded || snapshot == null) return;
        boolean newState = states.putIfAbsent(state, snapshot.url()) == null;
        if (via != null) transitions.add(new RunResult.Transition(parentState(c.parentStep()), state, via.label()));
        if (!newState) return;
        expand(snapshot, c, seq);
    }

    private String parentState(int step) {
        return steps.stream().filter(s -> s.sequence() == step).map(StepRecord::state).findFirst().orElse("");
    }

    /** A failure must happen twice before it is reported. */
    private StepEvaluation confirm(Candidate c, Navigation first, StepEvaluation eval) {
        browser.drainSignals();
        Navigation again = browser.open(c.url());
        StepEvaluation second = verdicts.evaluate(again, ExpectedOutcome.PAGE_LOADS, true, browser.drainSignals());
        Set<String> repeated = new HashSet<>();
        second.findings().forEach(f -> repeated.add(f.fingerprint()));
        List<Finding> confirmed = eval.findings().stream().filter(f -> repeated.contains(f.fingerprint())).toList();
        if (confirmed.isEmpty()) return new StepEvaluation(Verdict.INCONCLUSIVE, List.of(), "failure did not repeat");
        return new StepEvaluation(Verdict.FAIL, confirmed, "");
    }

    private void capture(int seq, List<Finding> findings, List<Signal> signals) {
        List<Finding> fresh = findings.stream().filter(f -> !observations.containsKey(f.fingerprint())).toList();
        if (fresh.isEmpty()) return;
        List<EvidenceRef> refs = new ArrayList<>();
        refs.add(evidence.text(EvidenceType.SIGNAL_LOG, seq, () -> signals.toString()));
        refs.add(evidence.text(EvidenceType.DOM_SNAPSHOT, seq, browser::domSnapshot));
        refs.add(evidence.binary(EvidenceType.SCREENSHOT, seq, browser.screenshot(masks)));
        for (Finding f : fresh) {
            Observation o = new Observation(f, seq, refs);
            observations.put(f.fingerprint(), o);
            findingListener.accept(o);
        }
    }

    private void expand(PageSnapshot snapshot, Candidate c, int seq) {
        int disclosures = 0;
        List<Control> inPlace = new ArrayList<>();
        for (Control control : snapshot.controls()) {
            if (control.kind() == ActionRequest.Kind.NAVIGATE_LINK && control.url() != null) {
                if (!isWeb(control.url())) continue;
                if (samePage(control.url(), snapshot.url())) {
                    if (hasFragment(control.url())) inPlace.add(control);
                    continue;
                }
                frontier.add(new Candidate(control.url(), c.depth() + 1, control, seq));
            } else if (control.kind() == ActionRequest.Kind.DISCLOSURE) {
                inPlace.add(control);
            } else {
                recordBlockedOnce(control, seq);
            }
        }
        for (Control control : inPlace) {
            if (disclosures++ >= MAX_DISCLOSURES_PER_STATE) break;
            disclose(snapshot, control, c, seq);
        }
    }

    /** Controls the policy refuses still count, so blocked coverage is visible. */
    private void recordBlockedOnce(Control control, int seq) {
        Decision d = gate.decideAction(control.toAction());
        if (d.allowed()) return;
        String key = control.kind() + "|" + control.label() + "|" + control.locators();
        if (!reportedBlocks.add(key)) return;
        add(new StepRecord(++sequence, seq, control.kind(), control.url() == null ? "" : control.url(), control.label(),
                control.locators(), Verdict.BLOCKED, 0, "", d.reason() + ": " + d.detail()));
    }

    /** Reopens the page so each in-place control starts from the same state. */
    private void disclose(PageSnapshot from, Control control, Candidate c, int parent) {
        Decision d = gate.decideAction(control.toAction());
        if (!d.allowed()) {
            recordBlockedOnce(control, parent);
            return;
        }
        Navigation nav = browser.open(from.url());
        if (nav.outcome() != Navigation.Outcome.LOADED) return;
        browser.drainSignals();
        try {
            browser.activate(control);
        } catch (RuntimeException e) {
            add(new StepRecord(++sequence, parent, control.kind(), from.url(), control.label(), control.locators(),
                    Verdict.INCONCLUSIVE, 0, from.fingerprint(), "control could not be used"));
            return;
        }
        PageSnapshot after = browser.snapshot();
        boolean changed = !after.fingerprint().equals(from.fingerprint());
        StepEvaluation eval = verdicts.evaluate(Navigation.loaded(200, after.url()), ExpectedOutcome.STATE_CHANGES, changed, browser.drainSignals());
        add(new StepRecord(++sequence, parent, control.kind(), after.url(), control.label(), control.locators(),
                eval.verdict(), 200, after.fingerprint(), eval.note()));
        int seq = sequence;
        if (states.putIfAbsent(after.fingerprint(), after.url()) != null) return;
        transitions.add(new RunResult.Transition(from.fingerprint(), after.fingerprint(), control.label()));
        for (Control next : after.controls()) {
            if (next.kind() == ActionRequest.Kind.NAVIGATE_LINK && next.url() != null && isWeb(next.url()) && !samePage(next.url(), after.url())) {
                frontier.add(new Candidate(next.url(), c.depth() + 1, next, seq));
            }
        }
    }

    private int record(Candidate c, Control via, Verdict verdict, int status, String state, String note) {
        add(new StepRecord(++sequence, c.parentStep(), via == null ? ActionRequest.Kind.NAVIGATE_LINK : via.kind(), c.url(),
                via == null ? "" : via.label(), via == null ? List.of() : via.locators(), verdict, status, state, note));
        return sequence;
    }

    private void add(StepRecord step) {
        steps.add(step);
        stepListener.accept(step);
    }

    private static boolean isWeb(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private static boolean samePage(String a, String b) {
        return Frontier.key(a).equals(Frontier.key(b));
    }

    private static boolean hasFragment(String url) {
        try {
            String f = new URI(url).getFragment();
            return f != null && !f.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
