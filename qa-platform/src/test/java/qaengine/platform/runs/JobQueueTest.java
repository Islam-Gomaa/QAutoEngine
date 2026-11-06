package qaengine.platform.runs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import qaengine.platform.IntegrationTestBase;

class JobQueueTest extends IntegrationTestBase {

    @Autowired JobQueue queue;
    @Autowired JdbcClient db;

    UUID org;
    UUID run;
    UUID user;
    UUID project;
    UUID environment;

    @BeforeEach
    void seed() {
        db.sql("TRUNCATE organization, app_user CASCADE").update();
        org = UUID.randomUUID();
        user = UUID.randomUUID();
        project = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        environment = UUID.randomUUID();
        db.sql("INSERT INTO organization (id, name) VALUES (?, 'o')").param(org).update();
        db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?, ?, 'x')").params(user, user + "@x.test").update();
        db.sql("INSERT INTO project (organization_id, id, name) VALUES (?,?, 'p')").params(org, project).update();
        db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?, 't')").params(org, target, project).update();
        db.sql("INSERT INTO environment (organization_id, id, target_id, name, classification, base_url) VALUES (?,?,?, 'e', 'LOCAL', 'http://127.0.0.1')").params(org, environment, target).update();
        run = newRun();
    }

    UUID newRun() {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO test_run (organization_id, id, project_id, environment_id, seed, idempotency_key, created_by) VALUES (?,?,?,?, 1, ?, ?)")
                .params(org, id, project, environment, id.toString(), user).update();
        return id;
    }

    String state(UUID job) {
        return db.sql("SELECT state FROM run_job WHERE id = ?").param(job).query(String.class).single();
    }

    void expireLease(UUID job) {
        db.sql("UPDATE run_job SET lease_expires_at = now() - interval '1 second' WHERE id = ?").param(job).update();
    }

    @Test
    void concurrentClaimsNeverShareAJob() throws Exception {
        for (int i = 0; i < 20; i++) queue.enqueue(org, newRun(), 2, 60);
        queue.enqueue(org, run, 2, 60);
        List<Callable<JobQueue.Claim>> workers = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String w = "w" + i;
            workers.add(() -> queue.claim(w).orElse(null));
        }
        List<UUID> claimed = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(16)) {
            for (var f : pool.invokeAll(workers)) if (f.get() != null) claimed.add(f.get().jobId());
        }
        assertEquals(21, claimed.size());
        assertEquals(21, claimed.stream().distinct().count());
    }

    @Test
    void onlyOneActiveJobPerRun() {
        queue.enqueue(org, run, 2, 60);
        assertThrows(DataIntegrityViolationException.class, () -> queue.enqueue(org, run, 2, 60));
    }

    @Test
    void heartbeatExtendsAndReportsCancel() {
        UUID job = queue.enqueue(org, run, 2, 60);
        var c = queue.claim("w").orElseThrow();
        assertTrue(queue.start(org, job, c.attempt()));
        assertEquals(Boolean.FALSE, queue.heartbeat(org, job, c.attempt()).orElseThrow());
        queue.requestCancel(org, run, "user");
        assertEquals(Boolean.TRUE, queue.heartbeat(org, job, c.attempt()).orElseThrow());
        assertEquals("RUNNING", state(job));
    }

    @Test
    void crashedReadOnlyJobRequeuesAndStaleAttemptIsFenced() {
        UUID job = queue.enqueue(org, run, 2, 60);
        var first = queue.claim("w1").orElseThrow();
        queue.start(org, job, first.attempt());
        expireLease(job);
        assertEquals(1, queue.reap());
        assertEquals("QUEUED", state(job));

        var second = queue.claim("w2").orElseThrow();
        assertEquals(2, second.attempt());

        // The first worker comes back after a long pause. All of its writes are refused.
        assertTrue(queue.heartbeat(org, job, first.attempt()).isEmpty());
        assertFalse(queue.owns(org, job, first.attempt()));
        assertFalse(queue.complete(org, job, first.attempt(), JobQueue.Terminal.SUCCEEDED, null, null));
        assertTrue(queue.complete(org, job, second.attempt(), JobQueue.Terminal.SUCCEEDED, null, "EXHAUSTED"));
        assertEquals("SUCCEEDED", state(job));
    }

    @Test
    void exhaustedAttemptsFail() {
        UUID job = queue.enqueue(org, run, 2, 60);
        for (int i = 0; i < 2; i++) {
            queue.claim("w").orElseThrow();
            expireLease(job);
            queue.reap();
        }
        assertEquals("FAILED", state(job));
        assertTrue(queue.claim("w").isEmpty());
    }

    @Test
    void mutationRunsAreNeverRetried() {
        UUID job = queue.enqueue(org, run, 1, 60);
        queue.claim("w").orElseThrow();
        expireLease(job);
        queue.reap();
        assertEquals("INTERRUPTED", state(job));
        assertTrue(queue.claim("w2").isEmpty());
    }

    @Test
    void cancelWhileQueuedIsImmediate() {
        UUID job = queue.enqueue(org, run, 2, 60);
        assertTrue(queue.requestCancel(org, run, "user"));
        assertEquals("CANCELLED", state(job));
        assertTrue(queue.claim("w").isEmpty());
        assertEquals("CANCELLED", db.sql("SELECT status FROM test_run WHERE id = ?").param(run).query(String.class).single());
    }

    @Test
    void cancelWithLostWorkerResolvesAtLeaseExpiry() {
        UUID job = queue.enqueue(org, run, 2, 60);
        queue.claim("w").orElseThrow();
        queue.requestCancel(org, run, "user");
        expireLease(job);
        queue.reap();
        assertEquals("CANCELLED", state(job));
    }

    @Test
    void passedDeadlineTimesOut() {
        UUID job = queue.enqueue(org, run, 2, 60);
        var c = queue.claim("w").orElseThrow();
        queue.start(org, job, c.attempt());
        db.sql("UPDATE run_job SET deadline_at = now() - interval '1 second' WHERE id = ?").param(job).update();
        queue.reap();
        assertEquals("TIMED_OUT", state(job));
    }

    @Test
    void healthyLeaseIsLeftAlone() {
        UUID job = queue.enqueue(org, run, 2, 60);
        queue.claim("w").orElseThrow();
        assertEquals(0, queue.reap());
        assertEquals("LEASED", state(job));
    }

    @Test
    void restoreInterruptsRunningJobs() {
        UUID job = queue.enqueue(org, run, 2, 60);
        queue.claim("w").orElseThrow();
        assertEquals(1, queue.interruptAllRunning());
        assertEquals("INTERRUPTED", state(job));
    }

    @Test
    void auditIsAppendOnly() {
        db.sql("INSERT INTO audit_event (organization_id, actor, action) VALUES (?, 'a', 'x')").param(org).update();
        assertThrows(Exception.class, () -> db.sql("UPDATE audit_event SET actor = 'b'").update());
        assertThrows(Exception.class, () -> db.sql("DELETE FROM audit_event").update());
    }

    @Test
    void productionCannotBeSetToMutate() {
        db.sql("UPDATE environment SET classification = 'PRODUCTION' WHERE id = ?").param(environment).update();
        assertThrows(Exception.class, () -> db.sql("INSERT INTO execution_policy (organization_id, environment_id, mode) VALUES (?,?, 'VALIDATION_ONLY')").params(org, environment).update());
    }

    @Test
    void crossOrganizationReferencesAreImpossible() {
        UUID other = UUID.randomUUID();
        db.sql("INSERT INTO organization (id, name) VALUES (?, 'other')").param(other).update();
        assertThrows(DataIntegrityViolationException.class, () ->
                db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?, 't')").params(other, UUID.randomUUID(), project).update());
    }
}
