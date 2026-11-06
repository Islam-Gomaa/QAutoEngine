package qaengine.platform.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qaengine.contracts.WorkerContracts.ApiProfile;
import qaengine.contracts.WorkerContracts.FindingReport;
import qaengine.contracts.WorkerContracts.HeartbeatResponse;
import qaengine.contracts.WorkerContracts.JobAssignment;
import qaengine.contracts.WorkerContracts.LoginProfile;
import qaengine.contracts.WorkerContracts.LoginSecret;
import qaengine.contracts.WorkerContracts.RunCompletion;
import qaengine.contracts.WorkerContracts.RunLimits;
import qaengine.contracts.WorkerContracts.RunScope;
import qaengine.contracts.WorkerContracts.StepReport;
import qaengine.core.evidence.EvidenceType;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.defects.DefectService;
import qaengine.platform.credentials.CredentialService;
import qaengine.platform.evidence.EvidenceService;
import qaengine.platform.runs.JobQueue;
import qaengine.platform.testcases.TestCaseService;

/**
 * The worker API. Bound to the internal port. Every call after claim carries a job token, and every
 * write is fenced: an attempt that lost its lease gets 409 and must stop.
 */
@RestController
@RequestMapping("/internal/v1/jobs")
public class WorkerController {

    private final JdbcClient db;
    private final JobTokens tokens;
    private final JobQueue queue;
    private final EvidenceService evidence;
    private final DefectService defects;
    private final TestCaseService testCases;
    private final AuditLog audit;
    private final CredentialService credentials;

    public WorkerController(JdbcClient db, JobTokens tokens, JobQueue queue, EvidenceService evidence, DefectService defects,
                            TestCaseService testCases, AuditLog audit, CredentialService credentials) {
        this.db = db;
        this.tokens = tokens;
        this.queue = queue;
        this.evidence = evidence;
        this.defects = defects;
        this.testCases = testCases;
        this.audit = audit;
        this.credentials = credentials;
    }

    private JobTokens.Scope scope(String authorization, UUID jobId) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        JobTokens.Scope s = tokens.verify(token).orElseThrow(Unauthorized::new);
        if (!s.jobId().equals(jobId)) throw new Unauthorized();
        if (!queue.owns(s.organizationId(), s.jobId(), s.attempt())) throw new Fenced();
        return s;
    }

    static final class Unauthorized extends RuntimeException { }
    static final class Fenced extends RuntimeException { }

    @org.springframework.web.bind.annotation.ExceptionHandler(Unauthorized.class)
    ResponseEntity<Void> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(Fenced.class)
    ResponseEntity<Void> fenced() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @PostMapping("/claim")
    public ResponseEntity<JobAssignment> claim(@RequestHeader(value = "X-Worker-Key", required = false) String key, @RequestBody Map<String, String> body) {
        if (!tokens.workerKeyMatches(key)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        var claim = queue.claim(body.getOrDefault("workerId", "unknown"));
        if (claim.isEmpty()) return ResponseEntity.noContent().build();
        JobQueue.Claim c = claim.get();
        String token = tokens.issue(new JobTokens.Scope(c.organizationId(), c.jobId(), c.attempt()));
        return ResponseEntity.ok(db.sql("""
                SELECT e.base_url, r.seed, ep.mode, e.classification, ep.include_paths, ep.exclude_paths, ep.mask_selectors,
                       ep.max_actions, ep.max_pages, ep.max_depth, ep.max_minutes,
                       e.login_method, e.login_path, e.login_submit_path, e.login_success_path,
                       (SELECT array_agg(origin) FROM allowed_host h WHERE h.organization_id = e.organization_id AND h.environment_id = e.id),
                       e.channel, e.api_spec_path, e.api_slow_ms, e.api_seed_paths, r.suite_id, r.max_pages
                FROM test_run r
                JOIN environment e ON e.organization_id = r.organization_id AND e.id = r.environment_id
                JOIN execution_policy ep ON ep.organization_id = e.organization_id AND ep.environment_id = e.id
                WHERE r.organization_id = ? AND r.id = ?
                """).params(c.organizationId(), c.runId()).query((rs, n) -> new JobAssignment(c.jobId(), c.runId(), c.attempt(), token,
                rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), array(rs, 16), array(rs, 5), array(rs, 6),
                new RunLimits(rs.getInt(8), rs.getObject(22) == null ? rs.getInt(9) : Math.min(rs.getInt(9), rs.getInt(22)), rs.getInt(10), rs.getInt(11), rs.getString(4).equals("PRODUCTION") ? 1 : 2), array(rs, 7),
                rs.getString(12) == null ? null : new LoginProfile(rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15)),
                rs.getString(17), new ApiProfile(rs.getString(18), rs.getLong(19), array(rs, 20)), scope(c.organizationId(), rs.getObject(21, UUID.class), rs.getString(1)))).single());
    }

    /** The last recorded step of each automated case in the suite says what to repeat. */
    private RunScope scope(UUID org, UUID suite, String baseUrl) {
        if (suite == null) return null;
        record Target(String url, String label) {}
        List<Target> targets = db.sql("""
                SELECT DISTINCT ON (c.id) v.steps -> -1 ->> 'url', v.steps -> -1 ->> 'label'
                FROM test_case c JOIN test_case_version v ON v.organization_id = c.organization_id AND v.test_case_id = c.id
                WHERE c.organization_id = ? AND c.suite_id = ? AND c.source = 'AUTOMATED' AND c.archived_at IS NULL
                ORDER BY c.id, v.version DESC
                """).params(org, suite).query((rs, n) -> new Target(rs.getString(1), rs.getString(2))).list();
        return new RunScope(targets.stream().map(Target::url).filter(java.util.Objects::nonNull).distinct().toList(),
                targets.stream().map(Target::label).filter(java.util.Objects::nonNull).distinct().toList());
    }

    private static List<String> array(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        var a = rs.getArray(column);
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    /** Once per attempt: a second request is refused, so a stolen token cannot keep asking. */
    @PostMapping("/{jobId}/login")
    @Transactional
    public ResponseEntity<LoginSecret> login(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID jobId) {
        JobTokens.Scope s = scope(auth, jobId);
        int claimed = db.sql("""
                UPDATE run_job SET credential_released_attempt = attempt
                WHERE organization_id = ? AND id = ? AND attempt = ? AND credential_released_attempt IS DISTINCT FROM attempt
                """).params(s.organizationId(), jobId, s.attempt()).update();
        if (claimed != 1) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        UUID run = runOf(s);
        UUID credential = db.sql("""
                SELECT e.login_credential_id FROM test_run r
                JOIN environment e ON e.organization_id = r.organization_id AND e.id = r.environment_id
                WHERE r.organization_id = ? AND r.id = ?
                """).params(s.organizationId(), run).query(UUID.class).optional().orElse(null);
        if (credential == null) return ResponseEntity.notFound().build();
        audit.record(s.organizationId(), "worker", "CREDENTIAL_RELEASED", credential.toString(), "run " + run + " attempt " + s.attempt());
        return ResponseEntity.ok(credentials.release(s.organizationId(), credential));
    }

    @PostMapping("/{jobId}/start")
    public ResponseEntity<Void> start(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID jobId) {
        JobTokens.Scope s = scope(auth, jobId);
        boolean ok = queue.start(s.organizationId(), jobId, s.attempt());
        if (ok) db.sql("UPDATE test_run r SET status = 'RUNNING' FROM run_job j WHERE j.organization_id = ? AND j.id = ? AND r.organization_id = j.organization_id AND r.id = j.run_id")
                .params(s.organizationId(), jobId).update();
        return ok ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @PostMapping("/{jobId}/heartbeat")
    public ResponseEntity<HeartbeatResponse> heartbeat(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID jobId) {
        JobTokens.Scope s = scope(auth, jobId);
        return queue.heartbeat(s.organizationId(), jobId, s.attempt())
                .map(cancel -> ResponseEntity.ok(new HeartbeatResponse(cancel, tokens.issue(s))))
                .orElse(ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    private UUID runOf(JobTokens.Scope s) {
        return db.sql("SELECT run_id FROM run_job WHERE organization_id = ? AND id = ?").params(s.organizationId(), s.jobId()).query(UUID.class).single();
    }

    private UUID projectOf(UUID org, UUID run) {
        return db.sql("SELECT project_id FROM test_run WHERE organization_id = ? AND id = ?").params(org, run).query(UUID.class).single();
    }

    @PostMapping("/{jobId}/steps")
    @Transactional
    public ResponseEntity<Void> steps(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID jobId, @RequestBody List<StepReport> steps) {
        JobTokens.Scope s = scope(auth, jobId);
        UUID run = runOf(s);
        for (StepReport r : steps) {
            db.sql("""
                    INSERT INTO run_step (organization_id, run_id, attempt, sequence, parent, kind, url, label, locators, verdict, status, state, note, duration_ms)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (organization_id, run_id, attempt, sequence) DO NOTHING
                    """).params(s.organizationId(), run, s.attempt(), r.sequence(), r.parent(), r.kind(), r.url(), r.label(),
                    r.locators().toArray(String[]::new), r.verdict(), r.status(), r.state(), r.note(), r.durationMs()).update();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{jobId}/evidence", consumes = "application/octet-stream")
    public ResponseEntity<Map<String, String>> upload(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID jobId,
                                                      @RequestParam int step, @RequestParam String type, @RequestParam String sha256, @RequestBody byte[] content) {
        JobTokens.Scope s = scope(auth, jobId);
        UUID id = evidence.ingest(s.organizationId(), runOf(s), s.attempt(), step, EvidenceType.valueOf(type), sha256, content);
        return ResponseEntity.ok(Map.of("evidenceId", id.toString()));
    }

    @PostMapping("/{jobId}/findings")
    @Transactional
    public ResponseEntity<Void> finding(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID jobId, @RequestBody FindingReport f) {
        JobTokens.Scope s = scope(auth, jobId);
        UUID org = s.organizationId();
        UUID run = runOf(s);
        UUID project = projectOf(org, run);
        UUID defect = defects.upsert(new DefectService.Observed(org, project, run, f.fingerprint(), f.type(), f.severity(), f.title(), f.url(), f.detail()));
        UUID finding = db.sql("""
                INSERT INTO finding (organization_id, id, run_id, attempt, step, fingerprint, defect_id) VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (organization_id, run_id, attempt, fingerprint) DO UPDATE SET step = EXCLUDED.step RETURNING id
                """).params(org, UUID.randomUUID(), run, s.attempt(), f.step(), f.fingerprint(), defect).query(UUID.class).single();
        for (String sha : f.evidenceSha256()) {
            db.sql("""
                    INSERT INTO finding_evidence (organization_id, finding_id, evidence_id)
                    SELECT organization_id, ?, id FROM evidence
                    WHERE organization_id = ? AND run_id = ? AND attempt = ? AND sha256 = ?
                    ON CONFLICT DO NOTHING
                    """).params(finding, org, run, s.attempt(), sha).update();
        }
        var saved = testCases.save(org, project, "defect:" + f.fingerprint(), "Reproduce: " + f.title() + " at " + path(f.url()), reproJson(org, run, s.attempt(), f.step()));
        testCases.link(org, defect, saved.testCaseId());
        testCases.recordResult(org, run, saved.versionId(), "FAIL");
        return ResponseEntity.noContent().build();
    }

    private String reproJson(UUID org, UUID run, int attempt, int step) {
        return db.sql("""
                WITH RECURSIVE path AS (
                    SELECT sequence, parent, kind, url, label, locators, 0 AS hop FROM run_step
                    WHERE organization_id = ? AND run_id = ? AND attempt = ? AND sequence = ?
                    UNION ALL
                    SELECT s.sequence, s.parent, s.kind, s.url, s.label, s.locators, path.hop + 1 FROM run_step s JOIN path ON s.sequence = path.parent
                    WHERE s.organization_id = ? AND s.run_id = ? AND s.attempt = ?
                )
                SELECT coalesce(jsonb_agg(jsonb_build_object('kind', kind, 'url', url, 'label', label, 'locators', locators) ORDER BY hop DESC), '[]'::jsonb)::text FROM path
                """).params(org, run, attempt, step, org, run, attempt).query(String.class).single();
    }

    /** Each case gets the worst verdict of the steps that repeated it, against its current version. */
    private void recordSuiteResults(UUID org, UUID run, int attempt, UUID suite) {
        record Case(UUID version, String url, String label) {}
        List<Case> cases = db.sql("""
                SELECT DISTINCT ON (c.id) v.id, v.steps -> -1 ->> 'url', v.steps -> -1 ->> 'label'
                FROM test_case c JOIN test_case_version v ON v.organization_id = c.organization_id AND v.test_case_id = c.id
                WHERE c.organization_id = ? AND c.suite_id = ? AND c.source = 'AUTOMATED' AND c.archived_at IS NULL
                ORDER BY c.id, v.version DESC
                """).params(org, suite).query((rs, n) -> new Case(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
        for (Case c : cases) {
            db.sql("""
                    SELECT verdict FROM run_step
                    WHERE organization_id = ? AND run_id = ? AND attempt = ? AND (url = ? OR (label <> '' AND label = ?))
                    ORDER BY array_position(ARRAY['FAIL','BLOCKED','INCONCLUSIVE','PASS'], verdict) LIMIT 1
                    """).params(org, run, attempt, c.url(), c.label()).query(String.class).optional()
                    .ifPresent(verdict -> testCases.recordResult(org, run, c.version(), verdict));
        }
    }

    private static String path(String url) {
        return url == null ? "" : url.replaceAll("^[a-z]+://[^/]+", "").replaceAll("\\?.*$", "");
    }

    /** Pages that loaded cleanly become regression cases, so a later failure has a baseline. */
    @PostMapping("/{jobId}/complete")
    @Transactional
    public ResponseEntity<Void> complete(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID jobId, @RequestBody RunCompletion body) {
        JobTokens.Scope s = scope(auth, jobId);
        UUID org = s.organizationId();
        UUID run = runOf(s);
        UUID project = projectOf(org, run);
        UUID suite = db.sql("SELECT suite_id FROM test_run WHERE organization_id = ? AND id = ?").params(org, run).query((rs, n) -> java.util.Optional.ofNullable(rs.getObject(1, UUID.class))).single().orElse(null);
        if (suite != null) {
            recordSuiteResults(org, run, s.attempt(), suite);
            JobQueue.Terminal done = JobQueue.Terminal.valueOf(body.outcome());
            if (!queue.complete(org, jobId, s.attempt(), done, null, body.stopReason())) throw new Fenced();
            audit.record(org, project, "worker", "RUN_FINISHED", run.toString(), done + " " + body.stopReason());
            return ResponseEntity.noContent().build();
        }
        record Passed(int sequence, String url) {}
        List<Passed> passed = db.sql("""
                SELECT min(sequence), url FROM run_step
                WHERE organization_id = ? AND run_id = ? AND attempt = ? AND verdict = 'PASS' AND kind = 'NAVIGATE_LINK'
                GROUP BY url
                """).params(org, run, s.attempt()).query((rs, n) -> new Passed(rs.getInt(1), rs.getString(2))).list();
        for (Passed p : passed) {
            var saved = testCases.save(org, project, "page:" + path(p.url()), "Page loads: " + path(p.url()), reproJson(org, run, s.attempt(), p.sequence()));
            testCases.recordResult(org, run, saved.versionId(), "PASS");
        }
        record ApiStep(int sequence, String label, String verdict) {}
        List<ApiStep> apiSteps = db.sql("""
                SELECT min(sequence), label, min(verdict) FROM run_step
                WHERE organization_id = ? AND run_id = ? AND attempt = ? AND kind = 'API_CALL' AND verdict IN ('PASS','FAIL')
                GROUP BY label
                """).params(org, run, s.attempt()).query((rs, n) -> new ApiStep(rs.getInt(1), rs.getString(2), rs.getString(3))).list();
        for (ApiStep a : apiSteps) {
            var saved = testCases.save(org, project, "api:" + a.label(), "API: " + a.label(), reproJson(org, run, s.attempt(), a.sequence()));
            testCases.recordResult(org, run, saved.versionId(), a.verdict());
        }
        JobQueue.Terminal outcome = JobQueue.Terminal.valueOf(body.outcome());
        if (!queue.complete(org, jobId, s.attempt(), outcome, null, body.stopReason())) throw new Fenced();
        audit.record(org, project, "worker", "RUN_FINISHED", run.toString(), outcome + " " + body.stopReason());
        return ResponseEntity.noContent().build();
    }
}
