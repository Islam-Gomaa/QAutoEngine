package qaengine.platform.runs;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class RunController {

    public record CreateRun(UUID environmentId, UUID suiteId, Integer maxPages) {}
    public record RunView(UUID id, UUID environmentId, String environmentName, String applicationName, String channel, UUID suiteId, String suiteName, String status,
                          OffsetDateTime createdAt, OffsetDateTime finishedAt, String stopReason, String startedBy, long passed, long failed, long inconclusive, long blocked) {}
    public record RunPage(List<RunView> items, long total) {}
    public record Progress(RunView run, int attempt, String jobState, String currentOperation, long steps, long defects, long durationSeconds) {}
    public record FindingView(UUID defectId, long number, String type, String severity, String title, String url, String detail, int step) {}
    public record StepView(int attempt, int sequence, int parent, String kind, String url, String label, List<String> locators,
                           String verdict, int status, String note, long durationMs) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final Access access;
    private final JobQueue queue;
    private final AuditLog audit;

    public RunController(JdbcClient db, Access access, JobQueue queue, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.queue = queue;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT r.id, r.environment_id, e.name, t.name, e.channel, r.suite_id, s.name, r.status, r.created_at, r.finished_at, r.stop_reason, u.email,
                   count(st.*) FILTER (WHERE st.verdict = 'PASS'), count(st.*) FILTER (WHERE st.verdict = 'FAIL'),
                   count(st.*) FILTER (WHERE st.verdict = 'INCONCLUSIVE'), count(st.*) FILTER (WHERE st.verdict = 'BLOCKED')
            FROM test_run r
            JOIN environment e ON e.organization_id = r.organization_id AND e.id = r.environment_id
            JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
            JOIN app_user u ON u.id = r.created_by
            LEFT JOIN test_suite s ON s.organization_id = r.organization_id AND s.id = r.suite_id
            LEFT JOIN run_step st ON st.organization_id = r.organization_id AND st.run_id = r.id
                 AND st.attempt = (SELECT max(x.attempt) FROM run_step x WHERE x.organization_id = r.organization_id AND x.run_id = r.id)
            """;
    private static final String GROUP = " GROUP BY r.organization_id, r.id, e.name, t.name, e.channel, s.name, u.email ";

    private static RunView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8),
                rs.getObject(9, OffsetDateTime.class), rs.getObject(10, OffsetDateTime.class), rs.getString(11), rs.getString(12), rs.getLong(13), rs.getLong(14), rs.getLong(15), rs.getLong(16));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<RunView> create(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId,
                                          @RequestHeader("Idempotency-Key") String key, @RequestBody CreateRun body) {
        access.require(p, projectId, Access.Level.TESTER);
        var existing = db.sql("SELECT id FROM test_run WHERE organization_id = ? AND idempotency_key = ?").params(p.organizationId(), key).query(UUID.class).optional();
        if (existing.isPresent()) return ResponseEntity.ok(view(p, projectId, existing.get()));

        record Target(String mode, int minutes, int maxPages, String baseUrl, String channel, boolean archived, boolean environmentActive, boolean applicationActive) {}
        var policy = db.sql("""
                SELECT ep.mode, ep.max_minutes, ep.max_pages, e.base_url, e.channel, e.archived_at IS NOT NULL, e.active, t.active FROM environment e
                JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                JOIN execution_policy ep ON ep.organization_id = e.organization_id AND ep.environment_id = e.id
                WHERE e.organization_id = ? AND e.id = ? AND t.project_id = ?
                """).params(p.organizationId(), body.environmentId(), projectId)
                .query((rs, n) -> new Target(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8))).optional()
                .orElseThrow(() -> new NotFoundException("Environment"));
        if ("AUTHORIZED_MUTATION".equals(policy.mode())) throw new ConflictException("Mutation runs are not available");
        if (policy.archived()) throw new ConflictException("This environment is archived");
        if (!policy.applicationActive()) throw new ConflictException("This application is inactive");
        if (!policy.environmentActive()) throw new ConflictException("This environment is inactive");
        // The worker would refuse too. Saying so here keeps a real target from ever being queued.
        if (!qaengine.core.policy.ExecutionLock.isLocal(policy.baseUrl())) throw new ConflictException("Real targets are locked until the pre-pilot security gate passes");
        if (body.maxPages() != null && (body.maxPages() < 1 || body.maxPages() > policy.maxPages())) throw new ConflictException("Page limit is outside the policy");
        if (body.suiteId() != null) {
            var suite = db.sql("""
                    SELECT s.channel, (SELECT count(*) FROM test_case c WHERE c.organization_id = s.organization_id AND c.suite_id = s.id AND c.source = 'AUTOMATED' AND c.archived_at IS NULL)
                    FROM test_suite s WHERE s.organization_id = ? AND s.project_id = ? AND s.id = ? AND s.archived_at IS NULL
                    """).params(p.organizationId(), projectId, body.suiteId()).query((rs, n) -> new Object[] {rs.getString(1), rs.getLong(2)}).optional()
                    .orElseThrow(() -> new NotFoundException("Suite"));
            if (!suite[0].equals(policy.channel())) throw new ConflictException("This suite is for a different channel");
            if ((Long) suite[1] == 0) throw new ConflictException("This suite has no automated test cases to run");
        }

        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO test_run (organization_id, id, project_id, environment_id, seed, idempotency_key, created_by, suite_id, max_pages)
                VALUES (?,?,?,?,?,?,?,?,?)
                """).params(p.organizationId(), id, projectId, body.environmentId(), RANDOM.nextLong(), key, p.userId(), body.suiteId(), body.maxPages()).update();
        queue.enqueue(p.organizationId(), id, 2, policy.minutes());
        audit.record(p.organizationId(), projectId, p.email(), "RUN_CREATED", id.toString(), policy.channel());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(p, projectId, id));
    }

    @GetMapping
    public RunPage list(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @RequestParam(required = false) String status, @RequestParam(required = false) String channel,
                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        access.require(p, projectId, Access.Level.VIEWER);
        String where = """
                 WHERE r.organization_id = :org AND r.project_id = :project AND (cast(:status as text) IS NULL OR r.status = cast(:status as text))
                   AND (cast(:channel as text) IS NULL OR e.channel = cast(:channel as text))
                """;
        int limit = Math.min(Math.max(size, 1), 200);
        long total = db.sql("SELECT count(*) FROM test_run r JOIN environment e ON e.organization_id = r.organization_id AND e.id = r.environment_id" + where)
                .param("org", p.organizationId()).param("project", projectId).param("status", status).param("channel", channel).query(Long.class).single();
        var items = db.sql(SELECT + where + GROUP + " ORDER BY r.created_at DESC LIMIT :limit OFFSET :offset").param("org", p.organizationId()).param("project", projectId).param("status", status)
                .param("channel", channel).param("limit", limit).param("offset", Math.max(page, 0) * limit).query((rs, n) -> map(rs)).list();
        return new RunPage(items, total);
    }

    @GetMapping("/{runId}")
    public RunView get(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId) {
        access.require(p, projectId, Access.Level.VIEWER);
        return view(p, projectId, runId);
    }

    private RunView view(Principal p, UUID projectId, UUID runId) {
        return db.sql(SELECT + " WHERE r.organization_id = ? AND r.project_id = ? AND r.id = ? " + GROUP)
                .params(p.organizationId(), projectId, runId).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> new NotFoundException("Run"));
    }

    /** Everything here is read from what the worker reported. There is no estimated percentage: an exploration's size is unknown until it ends. */
    @GetMapping("/{runId}/progress")
    public Progress progress(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId) {
        access.require(p, projectId, Access.Level.VIEWER);
        RunView run = view(p, projectId, runId);
        record Job(int attempt, String state) {}
        Job job = db.sql("SELECT attempt, state FROM run_job WHERE organization_id = ? AND run_id = ? ORDER BY created_at DESC LIMIT 1").params(p.organizationId(), runId)
                .query((rs, n) -> new Job(rs.getInt(1), rs.getString(2))).optional().orElse(new Job(0, "QUEUED"));
        String current = db.sql("SELECT coalesce(nullif(label, ''), url) FROM run_step WHERE organization_id = ? AND run_id = ? ORDER BY attempt DESC, sequence DESC LIMIT 1")
                .params(p.organizationId(), runId).query(String.class).optional().orElse("");
        long steps = run.passed() + run.failed() + run.inconclusive() + run.blocked();
        long defects = db.sql("SELECT count(DISTINCT defect_id) FROM finding WHERE organization_id = ? AND run_id = ?").params(p.organizationId(), runId).query(Long.class).single();
        OffsetDateTime end = run.finishedAt() == null ? OffsetDateTime.now() : run.finishedAt();
        return new Progress(run, job.attempt(), job.state(), current, steps, defects, java.time.Duration.between(run.createdAt(), end).toSeconds());
    }

    @GetMapping("/{runId}/findings")
    public List<FindingView> findings(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId) {
        access.require(p, projectId, Access.Level.VIEWER);
        view(p, projectId, runId);
        return db.sql("""
                SELECT d.id, d.number, d.type, d.severity, d.title, d.url, d.detail, min(f.step) FROM finding f
                JOIN defect d ON d.organization_id = f.organization_id AND d.id = f.defect_id
                WHERE f.organization_id = ? AND f.run_id = ?
                GROUP BY d.organization_id, d.id ORDER BY array_position(ARRAY['CRITICAL','HIGH','MEDIUM','LOW'], d.severity), d.number
                """).params(p.organizationId(), runId).query((rs, n) -> new FindingView(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getInt(8))).list();
    }

    @GetMapping("/{runId}/steps")
    public List<StepView> steps(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId) {
        access.require(p, projectId, Access.Level.VIEWER);
        view(p, projectId, runId);
        return db.sql("""
                SELECT attempt, sequence, parent, kind, url, label, locators, verdict, status, note, duration_ms FROM run_step
                WHERE organization_id = ? AND run_id = ? ORDER BY attempt, sequence
                """).params(p.organizationId(), runId).query((rs, n) -> new StepView(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4),
                rs.getString(5), rs.getString(6), Arrays.asList((String[]) rs.getArray(7).getArray()), rs.getString(8), rs.getInt(9), rs.getString(10), rs.getLong(11))).list();
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId) {
        access.require(p, projectId, Access.Level.TESTER);
        view(p, projectId, runId);
        return queue.requestCancel(p.organizationId(), runId, p.email())
                ? ResponseEntity.accepted().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
