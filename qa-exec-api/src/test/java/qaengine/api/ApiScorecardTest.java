package qaengine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import qaengine.core.policy.ScopedException;
import qaengine.core.policy.TargetScope;
import qaengine.core.verdict.Finding;
import qaengine.core.verdict.Verdict;
import qaengine.fixtures.FixtureServer;

/** Phase 11 acceptance, judged by the planted ground truth and the fixture's own request log. */
class ApiScorecardTest {

    static FixtureServer fixture;
    static RunResult first;
    static List<FixtureServer.Recorded> requests;
    @TempDir static Path evidence;

    static ActionGate gate(RunBudget budget, ScopedException... exceptions) {
        var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, TargetScope.of(fixture.baseUrl()), List.of(exceptions), Set.of(), budget);
        return new ActionGate(policy, Clock.systemUTC(), l -> { });
    }

    static ApiRunner runner(ActionGate gate, Path dir, ApiRunner.Config config) {
        var redactor = new Redactor(List.of(FixtureServer.API_ADMIN_TOKEN, FixtureServer.API_USER_TOKEN));
        return new ApiRunner(gate, new ApiClient(gate, fixture.baseUrl(), null), new ApiVerdictEngine(1000),
                new EvidenceCollector(new LocalEvidenceSink(dir), redactor, 1L << 30), config);
    }

    static ApiRunner.Config tokens() {
        return new ApiRunner.Config(null, FixtureServer.API_ADMIN_TOKEN, FixtureServer.API_USER_TOKEN, 0, List.of());
    }

    @BeforeAll
    static void run() throws Exception {
        fixture = FixtureServer.start();
        first = runner(gate(RunBudget.defaults()), evidence, tokens()).run();
        requests = fixture.requests();
    }

    @AfterAll
    static void stop() {
        fixture.close();
    }

    static Set<String> found(RunResult r) {
        return r.observations().stream().map(o -> o.finding().type() + " " + o.finding().url().replace(fixture.baseUrl(), "").replaceAll("\\d+$", "{n}"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void findsExactlyThePlantedDefects() {
        assertEquals(new TreeSet<>(Set.of(
                "API_SERVER_ERROR /api/v1/search",
                "API_INFO_LEAK /api/v1/search",
                "API_SCHEMA /api/v1/orders/{n}",
                "API_CONTENT_TYPE /api/v1/status",
                "API_UNDECLARED_STATUS /api/v1/reports/{n}",
                "API_AUTH_BYPASS /api/v1/admin/users",
                // True as well: an endpoint with no auth also lets a low-privilege token through.
                "API_AUTHORIZATION /api/v1/admin/users",
                "API_AUTHORIZATION /api/v1/admin/audit",
                "API_INPUT_VALIDATION /api/v1/limits",
                "API_INFO_LEAK /api/v1/debug",
                "API_SLOW /api/v1/slow",
                "API_HEADERS /api/v1/public")), found(first));
    }

    @Test
    void severityFollowsImpact() {
        var byType = first.observations().stream().collect(Collectors.toMap(o -> o.finding().type() + o.finding().url(), o -> o.finding().severity(), (a, b) -> a));
        assertTrue(first.observations().stream().anyMatch(o -> o.finding().type() == Finding.Type.API_AUTH_BYPASS && o.finding().severity().name().equals("CRITICAL")), byType.toString());
    }

    @Test
    void nothingDestructiveReachedTheServer() {
        for (var r : requests) {
            assertTrue(Set.of("GET", "HEAD", "OPTIONS").contains(r.method()), r.method() + " " + r.target());
            assertFalse(r.path().endsWith("/purge"), "documented destructive GET");
        }
    }

    @Test
    void refusedOperationsAreRecordedAsCoverageNotSkipped() {
        var blocked = first.steps().stream().filter(s -> s.verdict() == Verdict.BLOCKED).map(StepRecord::label).toList();
        assertTrue(blocked.stream().anyMatch(l -> l.contains("POST /api/v1/items")), blocked.toString());
        assertTrue(blocked.stream().anyMatch(l -> l.contains("DELETE /api/v1/items/{id}")));
        assertTrue(blocked.stream().anyMatch(l -> l.contains("PUT /api/v1/items/{id}")));
        assertTrue(blocked.stream().anyMatch(l -> l.contains("GET /api/v1/items/purge")));
    }

    @Test
    void readOnlyPostNeedsAnExplicitException(@TempDir Path dir) throws Exception {
        fixture.clear();
        var exception = new ScopedException("read", ScopedException.Purpose.READ_OPERATION, fixture.baseUrl(), "POST", "/api/v1/search-read", null,
                Instant.now().plusSeconds(60), 3, "test", ScopedException.ApproverRole.PROJECT_ADMIN);
        runner(gate(RunBudget.defaults(), exception), dir, tokens()).run();
        assertTrue(fixture.requests().stream().anyMatch(r -> r.method().equals("POST") && r.path().equals("/api/v1/search-read")));
        assertFalse(fixture.requests().stream().anyMatch(r -> r.method().equals("POST") && r.path().equals("/api/v1/items")));
        assertFalse(requests.stream().anyMatch(r -> r.path().equals("/api/v1/search-read") && !r.method().equals("OPTIONS")), "without the exception only the OPTIONS probe reached it: " + requests.stream().filter(r -> r.path().equals("/api/v1/search-read")).map(FixtureServer.Recorded::method).toList());
    }

    @Test
    void specServersAreIgnored() {
        assertTrue(first.steps().stream().allMatch(s -> s.url().startsWith(fixture.baseUrl())));
    }

    @Test
    void redirectIsNotFollowed() {
        assertTrue(first.steps().stream().anyMatch(s -> s.url().endsWith("/api/v1/moved") && s.status() == 302));
    }

    @Test
    void everyFindingHasEvidenceAndNoTokensLeak() throws Exception {
        assertFalse(first.observations().isEmpty());
        for (var o : first.observations()) {
            EvidenceRef ref = o.evidence().getFirst();
            assertEquals(EvidenceRef.Status.STORED, ref.status());
            String text = Files.readString(Path.of(ref.location()));
            assertTrue(text.contains("\"status\""), "exchange recorded");
            assertFalse(text.contains(FixtureServer.API_ADMIN_TOKEN));
            assertFalse(text.contains(FixtureServer.API_USER_TOKEN));
            assertFalse(text.toLowerCase().contains("authorization"));
        }
        assertFalse(first.toString().contains(FixtureServer.API_ADMIN_TOKEN));
    }

    @Test
    void responseTimesAreMeasured() {
        assertTrue(first.steps().stream().filter(s -> s.status() > 0).allMatch(s -> s.durationMs() >= 0));
        var slow = first.steps().stream().filter(s -> s.label().startsWith("RESPONSE_TIME")).toList();
        assertEquals(1, slow.size());
        assertTrue(slow.getFirst().durationMs() >= 1000);
    }

    @Test
    void sameInputGivesTheSameResult(@TempDir Path dir) {
        var second = runner(gate(RunBudget.defaults()), dir, tokens()).run();
        assertEquals(found(first), found(second));
        assertEquals(first.steps().stream().map(s -> s.label() + s.verdict() + s.status()).toList(),
                second.steps().stream().map(s -> s.label() + s.verdict() + s.status()).toList());
    }

    @Test
    void withoutCredentialsProtectedPositiveTestsAreNotInvented(@TempDir Path dir) {
        var r = runner(gate(RunBudget.defaults()), dir, new ApiRunner.Config(null, null, null, 0, List.of())).run();
        assertTrue(r.steps().stream().noneMatch(s -> s.label().equals("POSITIVE GET /api/v1/me")));
        assertTrue(r.steps().stream().anyMatch(s -> s.label().equals("NO_CREDENTIAL GET /api/v1/me") && s.verdict() == Verdict.PASS));
        assertTrue(found(r).contains("API_AUTH_BYPASS /api/v1/admin/users"));
    }

    @Test
    void budgetAndCancelEndTheRun(@TempDir Path dir) {
        var small = runner(gate(new RunBudget(5, 5, 5, Duration.ofMinutes(5), 1)), dir, tokens()).run();
        assertEquals(RunResult.StopReason.BUDGET, small.stop());
        var cancelled = runner(gate(RunBudget.defaults()), dir, tokens()).stopWhen(() -> true).run();
        assertEquals(RunResult.StopReason.CANCELLED, cancelled.stop());
    }

    @Test
    void withoutASpecLinksAreFollowedAndOnlyGenericRulesApply(@TempDir Path dir) throws Exception {
        try (var bare = new NoSpecFixture()) {
            var scope = TargetScope.of(bare.url());
            var policy = new ExecutionPolicy(ExecutionMode.READ_ONLY, EnvironmentClass.LOCAL, scope, List.of(), Set.of(), RunBudget.defaults());
            var gate = new ActionGate(policy, Clock.systemUTC(), l -> { });
            var r = new ApiRunner(gate, new ApiClient(gate, bare.url(), null), new ApiVerdictEngine(1000),
                    new EvidenceCollector(new LocalEvidenceSink(dir), new Redactor(List.of()), 1L << 30), new ApiRunner.Config(null, null, null, 0, List.of("/root"))).run();
            assertTrue(r.steps().stream().anyMatch(s -> s.url().endsWith("/child") && s.verdict() == Verdict.PASS));
            assertTrue(r.observations().stream().anyMatch(o -> o.finding().url().endsWith("/broken")), "invalid JSON is found");
        }
    }

    @Test
    void realTargetsStayLocked() {
        var policy = ExecutionPolicy.readOnly(EnvironmentClass.STAGING, TargetScope.of("https://api.example.com"));
        var gate = new ActionGate(policy, Clock.systemUTC(), l -> { });
        assertThrows(SecurityException.class, () -> new ApiClient(gate, "https://api.example.com", null));
    }

    @Test
    void outOfScopeRequestIsBlockedBeforeLeaving() {
        var gate = gate(RunBudget.defaults());
        var x = new ApiClient(gate, "http://blocked.invalid", null).send("GET", "/x", Map.of(), null);
        assertEquals(ApiClient.Exchange.Outcome.BLOCKED, x.outcome());
    }
}
