package qaengine.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qaengine.core.engine.ExplorationEngine;
import qaengine.core.engine.Observation;
import qaengine.core.engine.Replayer;
import qaengine.core.engine.RunResult;
import qaengine.core.engine.StepRecord;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.EvidenceRef;
import qaengine.core.evidence.LocalEvidenceSink;
import qaengine.core.evidence.Redactor;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.EnvironmentClass;
import qaengine.core.policy.ExecutionMode;
import qaengine.core.policy.ExecutionPolicy;
import qaengine.core.policy.RunBudget;
import qaengine.core.policy.TargetScope;
import qaengine.core.verdict.Finding;
import qaengine.core.verdict.Verdict;
import qaengine.core.verdict.VerdictEngine;
import qaengine.fixtures.FixtureServer;
import qaengine.fixtures.Scorecard;

/** Phases 4 and 5 acceptance: a full exploration scored against the planted ground truth. */
class ScorecardTest {

    static FixtureServer fixture;
    static RunResult first;
    static List<FixtureServer.Recorded> firstRequests;
    @TempDir static Path evidence;

    static RunResult run(long seed, RunBudget budget, Path dir) {
        var scope = TargetScope.of(fixture.baseUrl());
        var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, scope, List.of(), Set.of(), budget);
        var gate = new ActionGate(policy, Clock.systemUTC(), l -> { });
        var collector = new EvidenceCollector(new LocalEvidenceSink(dir), new Redactor(List.of(FixtureServer.TEST_PASSWORD)), 1L << 30);
        try (var browser = new PlaywrightBrowser(gate)) {
            return new ExplorationEngine(gate, browser, new VerdictEngine(scope), collector, seed, Clock.systemUTC(), List.of())
                    .explore(fixture.url("/"));
        }
    }

    @BeforeAll
    static void explore() throws Exception {
        fixture = FixtureServer.start();
        first = run(42, RunBudget.defaults(), evidence);
        firstRequests = fixture.requests();
    }

    @AfterAll
    static void stop() {
        fixture.close();
    }

    private static boolean found(Finding.Type type, String path) {
        return first.observations().stream().anyMatch(o -> o.finding().type() == type && o.finding().url().endsWith(path));
    }

    @Test
    void runsToExhaustionNotAfterOneAction() {
        assertEquals(RunResult.StopReason.EXHAUSTED, first.stop());
        assertTrue(first.steps().size() > 20, "steps: " + first.steps().size());
    }

    @Test
    void everyPlantedDefectIsFound() {
        assertTrue(found(Finding.Type.BROKEN_PAGE, "/missing-page"), "404");
        assertTrue(found(Finding.Type.SERVER_ERROR, "/crash"), "500");
        assertTrue(found(Finding.Type.SCRIPT_ERROR, "/defects/js-error"), "js");
        assertTrue(found(Finding.Type.CONSOLE_ERROR, "/defects/console-error"), "console");
        assertTrue(found(Finding.Type.FAILED_REQUEST, "/api/fail"), "xhr");
        assertTrue(found(Finding.Type.BROKEN_RESOURCE, "/missing-image.png"), "image");
    }

    @Test
    void noFalseFindings() {
        Set<String> expected = Set.of("/missing-page", "/crash", "/defects/js-error", "/defects/console-error", "/api/fail", "/missing-image.png", "/hostile");
        var unexpected = first.observations().stream().map(o -> o.finding().url().replace(fixture.baseUrl(), ""))
                .filter(u -> !expected.contains(u)).toList();
        assertEquals(List.of(), unexpected);
    }

    @Test
    void noTrapReachedTheServer() {
        var reached = firstRequests.stream().map(FixtureServer.Recorded::target).collect(Collectors.toSet());
        for (Scorecard.Item trap : Scorecard.of(Scorecard.Kind.TRAP)) {
            if (trap.path().startsWith("http") || trap.id().equals("T-HOSTILE-REDIRECT")) continue;
            assertFalse(reached.stream().anyMatch(r -> r.startsWith(trap.path())), trap.id());
        }
        assertTrue(firstRequests.stream().allMatch(r -> r.method().equals("GET") || r.method().equals("HEAD")), "only safe methods");
    }

    @Test
    void policyBlocksAreRecordedAsCoverage() {
        var blocked = first.steps().stream().filter(s -> s.verdict() == Verdict.BLOCKED).toList();
        assertTrue(blocked.stream().anyMatch(s -> s.note().contains("OUT_OF_SCOPE")), "external link");
        assertTrue(blocked.stream().anyMatch(s -> s.url().contains("/logout")), "logout");
        assertTrue(blocked.stream().anyMatch(s -> s.note().contains("ACTION_NOT_ALLOWED")), "buttons and forms");
    }

    @Test
    void modernDomContentIsDiscovered() {
        Set<String> visited = first.steps().stream().map(StepRecord::url).collect(Collectors.toSet());
        for (String page : List.of("/spa-deep", "/framed-deep", "/shadow-deep", "/tab-deep")) {
            assertTrue(visited.contains(fixture.url(page)), page);
        }
    }

    @Test
    void infiniteCalendarDoesNotTrapTheCrawl() {
        long calendar = first.steps().stream().filter(s -> s.url().contains("/calendar")).count();
        assertTrue(calendar <= 3, "calendar steps: " + calendar);
    }

    @Test
    void everyFindingHasEvidenceAndReproSteps() throws Exception {
        for (Observation o : first.observations()) {
            assertEquals(3, o.evidence().size(), o.finding().title());
            for (EvidenceRef ref : o.evidence()) {
                assertEquals(EvidenceRef.Status.STORED, ref.status());
                assertEquals(ref.sha256(), LocalEvidenceSink.sha256(Files.readAllBytes(Path.of(ref.location()))));
            }
            var path = first.pathTo(o.step());
            assertEquals(fixture.url("/"), path.getFirst().url());
        }
    }

    @Test
    void sameSeedGivesSameRun(@TempDir Path other) {
        fixture.clear();
        var second = run(42, RunBudget.defaults(), other);
        assertEquals(urls(first), urls(second));
        assertEquals(fingerprints(first), fingerprints(second));
    }

    private static List<String> urls(RunResult r) {
        return r.steps().stream().map(s -> s.kind() + s.url() + s.verdict()).toList();
    }

    private static Set<String> fingerprints(RunResult r) {
        return r.observations().stream().map(o -> o.finding().fingerprint()).collect(Collectors.toSet());
    }

    @Test
    void differentSeedFindsTheSameDefects(@TempDir Path other) {
        assertEquals(fingerprints(first), fingerprints(run(7, RunBudget.defaults(), other)));
    }

    @Test
    void budgetEndsTheRun(@TempDir Path other) {
        var r = run(1, new RunBudget(500, 3, 10, java.time.Duration.ofHours(1), 2), other);
        assertEquals(RunResult.StopReason.BUDGET, r.stop());
    }

    @Test
    void recordedStepsReplayTheFinding() {
        var scope = TargetScope.of(fixture.baseUrl());
        var gate = new ActionGate(ExecutionPolicy.readOnly(EnvironmentClass.LOCAL, scope), Clock.systemUTC(), l -> { });
        Observation crash = first.observations().stream().filter(o -> o.finding().url().endsWith("/crash")).findFirst().orElseThrow();
        try (var browser = new PlaywrightBrowser(gate)) {
            var replayed = new Replayer(gate, browser, new VerdictEngine(scope)).replay(first.pathTo(crash.step()));
            assertTrue(replayed.stream().anyMatch(f -> f.fingerprint().equals(crash.finding().fingerprint())));
        }
    }

    @Test
    void realTargetsAreLocked() {
        var gate = new ActionGate(ExecutionPolicy.readOnly(EnvironmentClass.STAGING, TargetScope.of("https://example.com")), Clock.systemUTC(), l -> { });
        assertThrows(SecurityException.class, () -> new PlaywrightBrowser(gate));
    }

    static RunResult visit(List<String> urls, Path dir) {
        var scope = TargetScope.of(fixture.baseUrl());
        var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, scope, List.of(), Set.of(), RunBudget.defaults());
        var gate = new ActionGate(policy, Clock.systemUTC(), l -> { });
        var collector = new EvidenceCollector(new LocalEvidenceSink(dir), new Redactor(List.of(FixtureServer.TEST_PASSWORD)), 1L << 30);
        try (var browser = new PlaywrightBrowser(gate)) {
            return new ExplorationEngine(gate, browser, new VerdictEngine(scope), collector, 1, Clock.systemUTC(), List.of()).visitOnly(urls);
        }
    }

    @Test
    void savedCasesRepeatOnlyTheirOwnPages(@TempDir Path dir) {
        RunResult r = visit(List.of(fixture.url("/")), dir);
        assertEquals(1, r.steps().size(), "the start page has links, and none are followed");
    }

    @Test
    void savedCasesStillPassTheGate(@TempDir Path dir) {
        RunResult r = visit(List.of("https://real-target.example/"), dir);
        assertTrue(r.steps().stream().noneMatch(s -> s.verdict() == Verdict.PASS), "an outside page is never visited as a pass");
    }
}
