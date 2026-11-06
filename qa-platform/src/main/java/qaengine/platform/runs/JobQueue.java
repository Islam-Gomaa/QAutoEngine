package qaengine.platform.runs;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import qaengine.platform.audit.AuditLog;

/**
 * PostgreSQL job queue (ADR-007). Delivery is at-least-once, so every write is fenced by attempt
 * number: a worker that lost its lease is refused. Time always comes from the database.
 * Runs with mutation permission are never retried automatically.
 */
@Component
public class JobQueue {

    public static final Duration LEASE = Duration.ofSeconds(60);
    private static final String LEASE_SQL = "now() + interval '" + LEASE.toSeconds() + " seconds'";

    /** A job a worker now holds. */
    public record Claim(UUID organizationId, UUID jobId, UUID runId, int attempt) {}

    public enum Terminal { SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }

    private final JdbcClient db;
    private final AuditLog audit;

    public JobQueue(JdbcClient db, AuditLog audit) {
        this.db = db;
        this.audit = audit;
    }

    /** Must run in the same transaction as the run it belongs to. */
    public UUID enqueue(UUID organizationId, UUID runId, int maxAttempts, int maxMinutes) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO run_job (organization_id, id, run_id, max_attempts, deadline_at)
                VALUES (?,?,?,?, now() + make_interval(mins => ?))
                """).params(organizationId, id, runId, maxAttempts, maxMinutes).update();
        return id;
    }

    /** Claims the oldest job in a short transaction. Never held open during execution. */
    @Transactional
    public Optional<Claim> claim(String workerId) {
        return db.sql("""
                UPDATE run_job j SET state = 'LEASED', attempt = attempt + 1, worker_id = ?,
                       lease_expires_at = %s
                WHERE (j.organization_id, j.id) = (
                    SELECT organization_id, id FROM run_job
                    WHERE state = 'QUEUED' AND cancel_requested_at IS NULL
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING organization_id, id, run_id, attempt
                """.formatted(LEASE_SQL))
                .param(workerId)
                .query((rs, n) -> new Claim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4)))
                .optional();
    }

    /** Moves a claimed job to RUNNING. Fenced. */
    public boolean start(UUID organizationId, UUID jobId, int attempt) {
        return db.sql("""
                UPDATE run_job SET state = 'RUNNING', lease_expires_at = %s
                WHERE organization_id = ? AND id = ? AND attempt = ? AND state = 'LEASED'
                """.formatted(LEASE_SQL)).params(organizationId, jobId, attempt).update() == 1;
    }

    /** Extends the lease. Empty means this attempt no longer owns the job and must stop. */
    public Optional<Boolean> heartbeat(UUID organizationId, UUID jobId, int attempt) {
        return db.sql("""
                UPDATE run_job SET lease_expires_at = %s
                WHERE organization_id = ? AND id = ? AND attempt = ? AND state IN ('LEASED','RUNNING')
                RETURNING cancel_requested_at IS NOT NULL
                """.formatted(LEASE_SQL)).params(organizationId, jobId, attempt)
                .query((rs, n) -> rs.getBoolean(1)).optional();
    }

    public boolean owns(UUID organizationId, UUID jobId, int attempt) {
        return db.sql("SELECT count(*) FROM run_job WHERE organization_id = ? AND id = ? AND attempt = ? AND state IN ('LEASED','RUNNING')")
                .params(organizationId, jobId, attempt).query(Long.class).single() == 1;
    }

    /** One atomic transition, valid only for the current attempt. */
    @Transactional
    public boolean complete(UUID organizationId, UUID jobId, int attempt, Terminal outcome, String errorClass, String stopReason) {
        int updated = db.sql("""
                UPDATE run_job SET state = ?, error_class = ?, lease_expires_at = NULL
                WHERE organization_id = ? AND id = ? AND attempt = ? AND state IN ('LEASED','RUNNING')
                """).params(outcome.name(), errorClass, organizationId, jobId, attempt).update();
        if (updated == 1) finishRun(organizationId, jobId, outcome.name(), stopReason);
        return updated == 1;
    }

    /** Queued jobs cancel at once. Running ones see this on their next heartbeat. */
    @Transactional
    public boolean requestCancel(UUID organizationId, UUID runId, String actor) {
        List<UUID> jobs = db.sql("""
                UPDATE run_job SET cancel_requested_at = now()
                WHERE organization_id = ? AND run_id = ? AND state IN ('QUEUED','LEASED','RUNNING')
                RETURNING id
                """).params(organizationId, runId).query(UUID.class).list();
        if (jobs.isEmpty()) return false;
        audit.record(organizationId, actor, "RUN_CANCEL_REQUESTED", runId.toString(), "");
        int queued = db.sql("""
                UPDATE run_job SET state = 'CANCELLED' WHERE organization_id = ? AND run_id = ? AND state = 'QUEUED'
                """).params(organizationId, runId).update();
        if (queued > 0) finishRun(organizationId, jobs.getFirst(), "CANCELLED", "cancelled while queued");
        return true;
    }

    /**
     * Handles expired leases and passed deadlines. A READ_ONLY job with attempts left requeues; a
     * mutation job (max_attempts 1) becomes INTERRUPTED for a person to look at.
     */
    @Transactional
    public int reap() {
        int changed = 0;
        for (Expired e : expired()) {
            String next;
            if (e.cancelRequested()) next = "CANCELLED";
            else if (e.pastDeadline()) next = "TIMED_OUT";
            else if (e.attempt() < e.maxAttempts()) next = "QUEUED";
            else next = e.maxAttempts() == 1 ? "INTERRUPTED" : "FAILED";

            db.sql("""
                    UPDATE run_job SET state = ?, worker_id = NULL, lease_expires_at = NULL, error_class = ?
                    WHERE organization_id = ? AND id = ?
                    """).params(next, next.equals("QUEUED") ? null : "LEASE_EXPIRED", e.organizationId(), e.jobId()).update();
            audit.record(e.organizationId(), "system", "JOB_LEASE_EXPIRED", e.jobId().toString(), "attempt " + e.attempt() + " -> " + next);
            if (!next.equals("QUEUED")) finishRun(e.organizationId(), e.jobId(), next, "lease expired");
            changed++;
        }
        return changed;
    }

    private record Expired(UUID organizationId, UUID jobId, int attempt, int maxAttempts, boolean cancelRequested, boolean pastDeadline) {}

    private List<Expired> expired() {
        return db.sql("""
                SELECT organization_id, id, attempt, max_attempts, cancel_requested_at IS NOT NULL, deadline_at < now()
                FROM run_job
                WHERE state IN ('LEASED','RUNNING') AND (lease_expires_at < now() OR deadline_at < now())
                FOR UPDATE SKIP LOCKED
                """).query((rs, n) -> new Expired(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getInt(4), rs.getBoolean(5), rs.getBoolean(6))).list();
    }

    /** After restoring a backup, running jobs cannot be trusted. */
    @Transactional
    public int interruptAllRunning() {
        return db.sql("UPDATE run_job SET state = 'INTERRUPTED', error_class = 'RESTORED_FROM_BACKUP' WHERE state IN ('LEASED','RUNNING')").update();
    }

    private void finishRun(UUID organizationId, UUID jobId, String status, String stopReason) {
        db.sql("""
                UPDATE test_run r SET status = ?, finished_at = now(), stop_reason = ?
                FROM run_job j
                WHERE j.organization_id = ? AND j.id = ? AND r.organization_id = j.organization_id AND r.id = j.run_id
                """).params(status, stopReason, organizationId, jobId).update();
    }
}
