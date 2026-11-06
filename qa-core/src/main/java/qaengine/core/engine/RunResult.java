package qaengine.core.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import qaengine.core.verdict.Verdict;

/** Everything a run produced. */
public record RunResult(List<StepRecord> steps, List<Observation> observations, Map<String, String> states,
                        List<Transition> transitions, StopReason stop) {

    public enum StopReason { EXHAUSTED, BUDGET, DEADLINE, CANCELLED }

    /** An edge in the application model. */
    public record Transition(String from, String to, String via) {}

    public RunResult {
        steps = List.copyOf(steps);
        observations = List.copyOf(observations);
        states = Map.copyOf(states);
        transitions = List.copyOf(transitions);
    }

    public Map<Verdict, Long> verdictCounts() {
        return steps.stream().collect(Collectors.groupingBy(StepRecord::verdict, Collectors.counting()));
    }

    /** The path from the start URL to a step, in order: these are the reproduction steps. */
    public List<StepRecord> pathTo(int step) {
        Map<Integer, StepRecord> bySeq = new HashMap<>();
        steps.forEach(s -> bySeq.put(s.sequence(), s));
        List<StepRecord> path = new ArrayList<>();
        for (StepRecord s = bySeq.get(step); s != null; s = bySeq.get(s.parent())) path.addFirst(s);
        return path;
    }
}
