package qaengine.web;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import qaengine.core.engine.ExplorationEngine;
import qaengine.core.engine.Observation;
import qaengine.core.engine.RunResult;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.LocalEvidenceSink;
import qaengine.core.evidence.Redactor;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.TargetScope;
import qaengine.core.verdict.VerdictEngine;

/** Local read-only exploration. Usage: Cli <start-url> <evidence-dir>. Loopback only. */
public final class Cli {

    private Cli() {}

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: Cli <start-url> <evidence-dir>");
            System.exit(2);
        }
        String origin = TargetScope.originOf(args[0]).orElseThrow(() -> new IllegalArgumentException("Not an http(s) URL"));
        TargetScope scope = TargetScope.of(origin);
        ExecutionPolicy policy = ExecutionPolicy.readOnly(EnvironmentClass.LOCAL, scope);
        ActionGate gate = new ActionGate(policy, Clock.systemUTC(), line -> { });
        var evidence = new EvidenceCollector(new LocalEvidenceSink(Path.of(args[1])), new Redactor(List.of()), 1L << 30);
        try (var browser = new PlaywrightBrowser(gate)) {
            RunResult result = new ExplorationEngine(gate, browser, new VerdictEngine(scope), evidence, 1L, Clock.systemUTC(), List.of()).explore(args[0]);
            System.out.println("Stopped: " + result.stop() + ", steps: " + result.steps().size() + ", states: " + result.states().size() + ", verdicts: " + result.verdictCounts());
            for (Observation o : result.observations()) {
                System.out.printf("%s %s %s (%s)%n", o.finding().severity(), o.finding().title(), o.finding().url(), o.finding().detail());
            }
        }
    }
}
