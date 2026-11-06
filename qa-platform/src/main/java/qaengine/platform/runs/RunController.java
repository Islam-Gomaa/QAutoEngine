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
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class RunController {

    public record CreateRun(UUID environmentId) {}
    public record RunView(UUID id, UUID environmentId, String status, OffsetDateTime createdAt, OffsetDateTime finishedAt, String stopReason) {}
    public record StepView(int attempt, int sequence, int parent, String kind, String url, String label, List<String> locators,
                           String verdict, int status, String note) {}

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

    private static final String RUN_COLUMNS = "id, environment_id, status, created_at, finished_at, stop_reason";

    private static RunView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class), rs.getString(6));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<RunView> create(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId,
                                          @RequestHeader("Idempotency-Key") String key, @RequestBody CreateRun body) {
        access.require(p, projectId, Access.Level.TESTER);
        var existing = db.sql("SELECT " + RUN_COLUMNS + " FROM test_run WHERE organization_id = ? AND idempotency_key = ?")
                .params(p.organizationId(), key).query((rs, n) -> map(rs)).optional();
        if (existing.isPresent()) return ResponseEntity.ok(existing.get());

        var policy = db.sql("""
                SELECT ep.mode, ep.max_minutes FROM environment e
                JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                JOIN execution_policy ep ON ep.organization_id = e.organization_id AND ep.environment_id = e.id
                WHERE e.organization_id = ? AND e.id = ? AND t.project_id = ?
                """).params(p.organizationId(), body.environmentId(), projectId)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getInt(2)}).optional()
                .orElseThrow(() -> new NotFoundException("Environment"));
        if ("AUTHORIZED_MUTATION".equals(policy[0])) throw new ConflictException("Mutation runs are not available");

        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO test_run (organization_id, id, project_id, environment_id, seed, idempotency_key, created_by)
                VALUES (?,?,?,?,?,?,?)
                """).params(p.organizationId(), id, projectId, body.environmentId(), RANDOM.nextLong(), key, p.userId()).update();
        queue.enqueue(p.organizationId(), id, 2, (Integer) policy[1]);
        audit.record(p.organizationId(), p.email(), "RUN_CREATED", id.toString(), "");
        return ResponseEntity.status(HttpStatus.CREATED).body(view(p, projectId, id));
    }

    @GetMapping
    public List<RunView> list(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.VIEWER);
        return db.sql("SELECT " + RUN_COLUMNS + " FROM test_run WHERE organization_id = ? AND project_id = ? ORDER BY created_at DESC LIMIT 200")
                .params(p.organizationId(), projectId).query((rs, n) -> map(rs)).list();
    }

    @GetMapping("/{runId}")
    public RunView get(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId) {
        access.require(p, projectId, Access.Level.VIEWER);
        return view(p, projectId, runId);
    }

    private RunView view(Principal p, UUID projectId, UUID runId) {
        return db.sql("SELECT " + RUN_COLUMNS + " FROM test_run WHERE organization_id = ? AND project_id = ? AND id = ?")
                .params(p.organizationId(), projectId, runId).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> new NotFoundException("Run"));
    }

    @GetMapping("/{runId}/steps")
    public List<StepView> steps(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId) {
        access.require(p, projectId, Access.Level.VIEWER);
        view(p, projectId, runId);
        return db.sql("""
                SELECT attempt, sequence, parent, kind, url, label, locators, verdict, status, note FROM run_step
                WHERE organization_id = ? AND run_id = ? ORDER BY attempt, sequence
                """).params(p.organizationId(), runId).query((rs, n) -> new StepView(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4),
                rs.getString(5), rs.getString(6), Arrays.asList((String[]) rs.getArray(7).getArray()), rs.getString(8), rs.getInt(9), rs.getString(10))).list();
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
