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
import qaengine.contracts.WorkerContracts;
import qaengine.contracts.WorkerContracts.ApiProfile;
import qaengine.contracts.WorkerContracts.AuthReport;
import qaengine.contracts.WorkerContracts.Locator;
import qaengine.contracts.WorkerContracts.OtpProfile;
import qaengine.contracts.WorkerContracts.OtpReply;
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
    private final tools.jackson.databind.ObjectMapper json;
    private final qaengine.platform.otp.OtpService otps;
    private final qaengine.platform.discovery.DiscoveryStore discoveries;

    private static final java.util.Set<String> AUTH_STATUSES = java.util.Set.of("NOT_STARTED", "IN_PROGRESS", "WAITING_FOR_OTP", "AUTHENTICATED", "FAILED", "EXPIRED",
            "REQUIRES_USER_ACTION", "CONNECTED");
    private static final java.util.Set<String> AUTH_FAILURES = java.util.Set.of("NETWORK_UNREACHABLE", "TLS_ERROR", "POLICY_REFUSED", "LOGIN_PAGE_UNAVAILABLE",
            "INVALID_CREDENTIALS", "VERIFICATION_FAILED", "SESSION_REJECTED", "ATTEMPT_LIMIT", "OTP_NOT_RECEIVED", "OTP_EXTRACTION_FAILED", "OTP_INVALID");
    private static final java.util.Set<String> AUTH_STAGES = java.util.Set.of("LOGIN_PAGE", "CREDENTIALS", "SUBMIT", "OTP", "VERIFY", "DONE");

    public WorkerController(JdbcClient db, JobTokens tokens, JobQueue queue, EvidenceService evidence, DefectService defects,
                            TestCaseService testCases, AuditLog audit, CredentialService credentials, tools.jackson.databind.ObjectMapper json,
                            qaengine.platform.otp.OtpService otps, qaengine.platform.discovery.DiscoveryStore discoveries) {
        this.db = db;
        this.tokens = tokens;
        this.queue = queue;
        this.evidence = evidence;
        this.defects = defects;
        this.testCases = testCases;
        this.audit = audit;
        this.credentials = credentials;
        this.json = json;
        this.otps = otps;
        this.discoveries = discoveries;
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
        // The worker reads the same approved-hosts file. Different versions would disagree about where a run may go,
        // so no job is handed to a worker that has not loaded the platform's version: restart them together.
        String expected = qaengine.core.policy.ExecutionLock.approved().fingerprint();
        String theirs = body.getOrDefault("approvedHosts", qaengine.core.policy.ApprovedHosts.none().fingerprint());
        if (!expected.equals(theirs)) return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
        var claim = queue.claim(body.getOrDefault("workerId", "unknown"));
        if (claim.isEmpty()) return ResponseEntity.noContent().build();
        JobQueue.Claim c = claim.get();
        // Eligibility was decided when the run was created; it is decided again now, as the job leaves the platform.
        // A run queued before its authorization was revoked or expired, or its environment deactivated, never starts.
        String refusal = refusal(c.organizationId(), c.runId());
        if (refusal != null) {
            queue.complete(c.organizationId(), c.jobId(), c.attempt(), JobQueue.Terminal.FAILED, "NOT_ELIGIBLE", refusal);
            audit.record(c.organizationId(), "worker", "RUN_REFUSED_AT_CLAIM", c.runId().toString(), refusal);
            return ResponseEntity.noContent().build();
        }
        String token = tokens.issue(new JobTokens.Scope(c.organizationId(), c.jobId(), c.attempt()));
        return ResponseEntity.ok(db.sql("""
                SELECT e.base_url, r.seed, ep.mode, e.classification, ep.include_paths, ep.exclude_paths, ep.mask_selectors,
                       ep.max_actions, ep.max_pages, ep.max_depth, ep.max_minutes,
                       ap.method, ap.login_path, ap.submit_path, ap.success_path, ap.locators::text, ap.login_timeout_seconds, r.kind,
                       (SELECT array_agg(origin) FROM allowed_host h WHERE h.organization_id = e.organization_id AND h.environment_id = e.id),
                       e.channel, e.api_spec_path, e.api_slow_ms, e.api_seed_paths, r.suite_id, r.max_pages,
                       ap.otp_locators::text, ap.otp_submit_path, ap.otp_resend_path, ap.otp_max_resends,
                       (SELECT m.timeout_seconds FROM otp_mailbox m WHERE m.organization_id = ap.organization_id AND m.auth_profile_id = ap.id),
                       CASE WHEN e.authorization_reference IS NULL THEN NULL ELSE e.authorization_expires_at END
                FROM test_run r
                JOIN environment e ON e.organization_id = r.organization_id AND e.id = r.environment_id
                JOIN execution_policy ep ON ep.organization_id = e.organization_id AND ep.environment_id = e.id
                LEFT JOIN auth_profile ap ON ap.organization_id = r.organization_id AND ap.id = r.auth_profile_id
                WHERE r.organization_id = ? AND r.id = ?
                """).params(c.organizationId(), c.runId()).query((rs, n) -> new JobAssignment(c.jobId(), c.runId(), c.attempt(), token,
                rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), array(rs, 19), array(rs, 5), array(rs, 6),
                new RunLimits(rs.getInt(8), rs.getObject(25) == null ? rs.getInt(9) : Math.min(rs.getInt(9), rs.getInt(25)), rs.getInt(10), rs.getInt(11), rs.getString(4).equals("PRODUCTION") ? 1 : 2), array(rs, 7),
                rs.getString(12) == null ? null : login(rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16), rs.getInt(17),
                        rs.getString(26), rs.getString(27), rs.getString(28), rs.getInt(29), (Integer) rs.getObject(30)),
                rs.getString(20), new ApiProfile(rs.getString(21), rs.getLong(22), array(rs, 23)), scope(c.organizationId(), rs.getObject(24, UUID.class), rs.getString(1)),
                rs.getString(18), scripts(c.organizationId(), rs.getObject(24, UUID.class)),
                rs.getObject(31) == null ? null : rs.getObject(31, java.time.OffsetDateTime.class).toInstant().toString())).single());
    }

    /** The profile as the worker needs it. The locators were validated when they were saved. */
    @SuppressWarnings("unchecked")
    private LoginProfile login(String method, String loginPath, String submitPath, String successPath, String locators, int timeout,
                               String otpLocators, String otpSubmitPath, String otpResendPath, int maxResends, Integer mailboxTimeout) {
        Map<String, Map<String, String>> l = json.readValue(locators, Map.class);
        java.util.function.Function<String, Locator> at = k -> l.get(k) == null ? null : new Locator(l.get(k).get("strategy"), l.get(k).get("value"), l.get(k).get("name"));
        OtpProfile otp = null;
        if ("PASSWORD_EMAIL_OTP".equals(method)) {
            Map<String, Map<String, String>> o = json.readValue(otpLocators, Map.class);
            java.util.function.Function<String, Locator> code = k -> o.get(k) == null ? null : new Locator(o.get(k).get("strategy"), o.get(k).get("value"), o.get(k).get("name"));
            // Longer than the platform will wait, by mailbox or by a person, so the platform's answer always comes first.
            int wait = (int) Math.max(mailboxTimeout == null ? 0 : mailboxTimeout, qaengine.platform.otp.OtpService.MANUAL_WAIT.toSeconds()) + 30;
            otp = new OtpProfile(code.apply("field"), code.apply("submit"), code.apply("failure"), code.apply("resend"), otpSubmitPath, otpResendPath, maxResends, wait);
        }
        return new LoginProfile(method, loginPath, submitPath, successPath, at.apply("username"), at.apply("password"), at.apply("submit"), at.apply("success"), at.apply("failure"),
                timeout, otp);
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

    /**
     * Why this run's environment may not run now, or null when it may: the same decision as run creation, taken again
     * with the environment as it is at this moment. Used before a job is handed out and before a credential is released.
     */
    private String refusal(UUID org, UUID run) {
        record Now(String baseUrl, String reference, java.time.OffsetDateTime expiresAt, boolean archived, boolean active, boolean applicationActive, String mode) {}
        Now now = db.sql("""
                SELECT e.base_url, e.authorization_reference, e.authorization_expires_at, e.archived_at IS NOT NULL, e.active, t.active, ep.mode
                FROM test_run r JOIN environment e ON e.organization_id = r.organization_id AND e.id = r.environment_id
                JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                JOIN execution_policy ep ON ep.organization_id = e.organization_id AND ep.environment_id = e.id
                WHERE r.organization_id = ? AND r.id = ?
                """).params(org, run).query((rs, n) -> new Now(rs.getString(1), rs.getString(2), rs.getObject(3, java.time.OffsetDateTime.class), rs.getBoolean(4),
                rs.getBoolean(5), rs.getBoolean(6), rs.getString(7))).optional().orElse(null);
        if (now == null) return "The environment no longer exists";
        if (now.archived()) return "This environment is archived";
        if (!now.applicationActive()) return "This application is inactive";
        if (!now.active()) return "This environment is inactive";
        if ("AUTHORIZED_MUTATION".equals(now.mode())) return "Mutation runs are not available";
        var eligibility = qaengine.platform.targets.Eligibility.of(now.baseUrl(), now.reference(), now.expiresAt(), java.time.Instant.now());
        return eligibility.executable() ? null : qaengine.platform.targets.Eligibility.refusal(eligibility.reason());
    }

    /** The approved, executable cases of the suite: the latest version of each, as a person approved it. Validated before they leave. */
    private List<WorkerContracts.ScriptJob> scripts(UUID org, UUID suite) {
        if (suite == null) return List.of();
        return db.sql("""
                SELECT DISTINCT ON (c.id) v.id, c.title, v.execution_mode, v.script::text
                FROM test_case c JOIN test_case_version v ON v.organization_id = c.organization_id AND v.test_case_id = c.id
                WHERE c.organization_id = ? AND c.suite_id = ? AND c.source <> 'AUTOMATED' AND %s
                ORDER BY c.id, v.version DESC
                """.formatted(TestCaseService.RUNNABLE)).params(org, suite)
                .query((rs, n) -> new WorkerContracts.ScriptJob(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4))).list().stream()
                .filter(j -> j.scriptJson() != null)
                .peek(j -> qaengine.platform.testcases.ScriptJson.parse(json, j.scriptJson()))
                .toList();
    }

    /** One scripted case's verdict, for a version that belongs to this run's suite and nothing else. */
    @PostMapping("/{jobId}/scripts")
    @Transactional
    public ResponseEntity<Void> scriptResult(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID jobId,
                                             @RequestBody WorkerContracts.ScriptResult r) {
        JobTokens.Scope s = scope(auth, jobId);
        UUID run = runOf(s);
        boolean ours = db.sql("""
                SELECT count(*) FROM test_case_version v JOIN test_case c ON c.organization_id = v.organization_id AND c.id = v.test_case_id
                JOIN test_run r ON r.organization_id = c.organization_id AND r.suite_id = c.suite_id
                WHERE v.organization_id = ? AND v.id = ? AND r.id = ? AND v.script IS NOT NULL
                """).params(s.organizationId(), r.caseVersionId(), run).query(Long.class).single() == 1;
        if (!ours) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (!java.util.Set.of("PASS", "FAIL", "INCONCLUSIVE", "BLOCKED").contains(r.verdict())) return ResponseEntity.badRequest().build();
        if (r.failureClass() != null && !FAILURE_CLASSES.contains(r.failureClass())) return ResponseEntity.badRequest().build();
        if (r.verdict().equals("PASS") != (r.failureClass() == null)) return ResponseEntity.badRequest().build();
        String summary = r.summary() == null ? "" : r.summary().length() > 1000 ? r.summary().substring(0, 1000) : r.summary();
        testCases.recordResult(s.organizationId(), run, r.caseVersionId(), r.verdict(), r.failureClass(), summary);
        return ResponseEntity.noContent().build();
    }

    private static final java.util.Set<String> FAILURE_CLASSES = java.util.Arrays.stream(qaengine.core.script.FailureClass.values()).map(Enum::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

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
        // No secret leaves for an environment that may not run now, whatever happened since the job was claimed.
        String refusal = refusal(s.organizationId(), run);
        if (refusal != null) {
            audit.record(s.organizationId(), "worker", "CREDENTIAL_REFUSED", run.toString(), "run " + run + " attempt " + s.attempt() + ": " + refusal);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // The run's own profile, fixed when it was created. A profile or credential disabled since then is not released.
        record Login(UUID credential, boolean usable) {}
        Login login = db.sql("""
                SELECT ap.credential_id, ap.enabled AND c.enabled FROM test_run r
                JOIN auth_profile ap ON ap.organization_id = r.organization_id AND ap.id = r.auth_profile_id
                JOIN credential c ON c.organization_id = ap.organization_id AND c.id = ap.credential_id
                WHERE r.organization_id = ? AND r.id = ?
                """).params(s.organizationId(), run).query((rs, n) -> new Login(rs.getObject(1, UUID.class), rs.getBoolean(2))).optional().orElse(null);
        if (login == null) return ResponseEntity.notFound().build();
        if (!login.usable()) {
            audit.record(s.organizationId(), "worker", "CREDENTIAL_REFUSED", login.credential().toString(), "run " + run + " attempt " + s.attempt() + ": disabled");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UUID credential = login.credential();
        audit.record(s.organizationId(), "worker", "CREDENTIAL_RELEASED", credential.toString(), "run " + run + " attempt " + s.attempt());
        return ResponseEntity.ok(credentials.release(s.organizationId(), credential));
    }

    /**
     * The worker's account of one sign-in attempt or connection check. Values outside the known sets are
     * refused, and the detail is capped: it came from a worker that handled hostile pages.
     */
    @PostMapping("/{jobId}/auth")
    @Transactional
    public ResponseEntity<Void> auth(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID jobId, @RequestBody AuthReport r) {
        JobTokens.Scope s = scope(authorization, jobId);
        int signIn = r.signIn() == null || r.signIn() <= 0 ? 1 : r.signIn(); // workers from before signing in again send no number
        if (!AUTH_STATUSES.contains(r.status()) || (r.failure() != null && !AUTH_FAILURES.contains(r.failure())) || !AUTH_STAGES.contains(r.stage()) || signIn > 2) {
            return ResponseEntity.badRequest().build();
        }
        UUID run = runOf(s);
        var suggestions = new java.util.LinkedHashMap<String, Map<String, String>>();
        if (r.suggestions() != null) {
            r.suggestions().forEach((field, l) -> {
                if (!java.util.Set.of("username", "password", "submit").contains(field) || l == null) return;
                try {
                    var spec = new qaengine.core.auth.LocatorSpec(qaengine.core.auth.LocatorSpec.Strategy.valueOf(l.strategy()), l.value(), l.name());
                    var m = new java.util.LinkedHashMap<String, String>();
                    m.put("strategy", spec.strategy().name());
                    m.put("value", spec.value());
                    if (spec.name() != null) m.put("name", spec.name());
                    suggestions.put(field, m);
                } catch (RuntimeException ignored) {
                    // A suggestion that would not be accepted as a locator is not offered.
                }
            });
        }
        String detail = r.detail() == null ? "" : r.detail().length() > 500 ? r.detail().substring(0, 500) : r.detail();
        db.sql("""
                INSERT INTO auth_attempt (organization_id, id, run_id, attempt, sign_in, environment_id, auth_profile_id, status, failure, stage, detail, duration_ms, suggestions)
                SELECT r.organization_id, ?, r.id, ?, ?, r.environment_id, r.auth_profile_id, ?, ?, ?, ?, ?, ?::jsonb FROM test_run r WHERE r.organization_id = ? AND r.id = ?
                ON CONFLICT (organization_id, run_id, attempt, sign_in) DO UPDATE SET status = EXCLUDED.status, failure = EXCLUDED.failure, stage = EXCLUDED.stage,
                    detail = EXCLUDED.detail, duration_ms = EXCLUDED.duration_ms, suggestions = EXCLUDED.suggestions
                """).params(UUID.randomUUID(), s.attempt(), signIn, r.status(), r.failure(), r.stage(), detail, Math.max(0, r.durationMs()), json.writeValueAsString(suggestions),
                s.organizationId(), run).update();
        audit.record(s.organizationId(), "worker", "AUTH_ATTEMPT_RECORDED", run.toString(), (signIn == 2 ? "signed in again: " : "") + r.status() + (r.failure() == null ? "" : " " + r.failure()));
        return ResponseEntity.noContent().build();
    }

    /** The sign-in is about to make the target send a code: mail from before now, and codes typed before now, no longer count. */
    @PostMapping("/{jobId}/otp/request")
    @Transactional
    public ResponseEntity<Void> otpRequested(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID jobId) {
        JobTokens.Scope s = scope(auth, jobId);
        otps.requested(s.organizationId(), runOf(s), s.attempt());
        return ResponseEntity.noContent().build();
    }

    /** One poll for the code, only for the attempt holding the lease. The code is in this response and nowhere else. */
    @PostMapping("/{jobId}/otp")
    @Transactional
    public ResponseEntity<OtpReply> otp(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID jobId) {
        JobTokens.Scope s = scope(auth, jobId);
        var r = otps.poll(s.organizationId(), runOf(s), s.attempt());
        return ResponseEntity.ok(new OtpReply(r.code(), r.failure(), r.detail(), r.retryAfterSeconds(), r.waitingForPerson()));
    }

    /** One page a discovery run found. Only from a discovery run's current attempt. */
    @PostMapping("/{jobId}/discovery/pages")
    @Transactional
    public ResponseEntity<Void> discoveredPage(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID jobId,
                                               @RequestBody qaengine.contracts.WorkerContracts.DiscoveredPage page) {
        JobTokens.Scope s = scope(auth, jobId);
        UUID run = runOf(s);
        if (!isDiscovery(s.organizationId(), run)) return ResponseEntity.status(HttpStatus.CONFLICT).build();
        try {
            discoveries.page(s.organizationId(), run, s.attempt(), page);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/discovery/edges")
    @Transactional
    public ResponseEntity<Void> discoveredEdges(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID jobId,
                                                @RequestBody List<qaengine.contracts.WorkerContracts.DiscoveredEdge> edges) {
        JobTokens.Scope s = scope(auth, jobId);
        UUID run = runOf(s);
        if (!isDiscovery(s.organizationId(), run)) return ResponseEntity.status(HttpStatus.CONFLICT).build();
        try {
            discoveries.edges(s.organizationId(), run, s.attempt(), edges);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.noContent().build();
    }

    private boolean isDiscovery(UUID org, UUID run) {
        return "DISCOVERY".equals(db.sql("SELECT kind FROM test_run WHERE organization_id = ? AND id = ?").params(org, run).query(String.class).single());
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
        var alive = queue.heartbeat(s.organizationId(), jobId, s.attempt());
        if (alive.isEmpty()) return ResponseEntity.status(HttpStatus.CONFLICT).build();
        // Revocation, expiry or deactivation since the job started stops it at its next heartbeat: within one interval.
        UUID run = runOf(s);
        String refusal = refusal(s.organizationId(), run);
        if (refusal != null && !alive.get()) {
            queue.requestCancel(s.organizationId(), run, "policy");
            audit.record(s.organizationId(), "worker", "RUN_STOPPED_NOT_ELIGIBLE", run.toString(), refusal);
        }
        return ResponseEntity.ok(new HeartbeatResponse(alive.get() || refusal != null, tokens.issue(s)));
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
        if (isDiscovery(org, run)) {
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
