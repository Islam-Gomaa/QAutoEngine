package qaengine.platform.defects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/defects")
public class DefectController {

    public record DefectView(UUID id, long number, String type, String severity, String priority, String title, String url, String detail, String expectedResult, String status, String module,
                             String source, int occurrences, UUID firstSeenRun, UUID lastSeenRun, String application, OffsetDateTime createdAt) {}
    public record DefectPage(List<DefectView> items, long total) {}
    public record Trace(DefectView defect, List<FindingTrace> findings, List<CaseLink> testCases) {}
    public record CaseLink(UUID id, String title) {}
    public record FindingTrace(UUID runId, int attempt, int step, List<EvidenceLink> evidence, List<ReproStep> reproduction) {}
    public record EvidenceLink(UUID id, String type, String sha256, long size) {}
    public record ReproStep(int sequence, String kind, String url, String label) {}
    public record Update(String status, String priority, String severity, String module, String expectedResult, String title) {}
    public record Create(@NotBlank @Size(max = 240) String title, String detail, String expectedResult, String severity, String priority, String module, String url) {}

    private static final Set<String> STATUSES = Set.of("OPEN", "TRIAGED", "FIXED", "WONT_FIX");
    private static final Set<String> LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Map<String, String> SORTS = Map.of("number", "d.number", "severity", "array_position(ARRAY['CRITICAL','HIGH','MEDIUM','LOW'], d.severity)",
            "priority", "array_position(ARRAY['CRITICAL','HIGH','MEDIUM','LOW'], d.priority)", "status", "d.status", "title", "d.title", "createdAt", "d.created_at");

    private static final String SELECT = """
            SELECT d.id, d.number, d.type, d.severity, d.priority, d.title, d.url, d.detail, d.expected_result, d.status, d.module, d.source, d.occurrences,
                   d.first_seen_run, d.last_seen_run,
                   (SELECT t.name FROM test_run r JOIN environment e ON e.organization_id = r.organization_id AND e.id = r.environment_id
                    JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                    WHERE r.organization_id = d.organization_id AND r.id = d.last_seen_run),
                   d.created_at
            FROM defect d
            """;

    private final JdbcClient db;
    private final Access access;
    private final AuditLog audit;

    public DefectController(JdbcClient db, Access access, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.audit = audit;
    }

    private static DefectView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DefectView(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getInt(13), rs.getObject(14, UUID.class), rs.getObject(15, UUID.class), rs.getString(16), rs.getObject(17, OffsetDateTime.class));
    }

    @GetMapping
    public DefectPage list(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @RequestParam(required = false) String q, @RequestParam(required = false) String status,
                           @RequestParam(required = false) String severity, @RequestParam(required = false) String priority, @RequestParam(required = false) UUID runId,
                           @RequestParam(defaultValue = "number") String sort, @RequestParam(defaultValue = "desc") String order,
                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        access.require(p, projectId, Access.Level.VIEWER);
        String where = """
                 WHERE d.organization_id = :org AND d.project_id = :project
                   AND (cast(:q as text) IS NULL OR d.title ILIKE '%' || cast(:q as text) || '%' OR d.url ILIKE '%' || cast(:q as text) || '%' OR d.number::text = cast(:q as text))
                   AND (cast(:status as text) IS NULL OR d.status = cast(:status as text))
                   AND (cast(:severity as text) IS NULL OR d.severity = cast(:severity as text))
                   AND (cast(:priority as text) IS NULL OR d.priority = cast(:priority as text))
                   AND (cast(:run as uuid) IS NULL OR EXISTS (SELECT 1 FROM finding f WHERE f.organization_id = d.organization_id AND f.defect_id = d.id AND f.run_id = cast(:run as uuid)))
                """;
        String blankQ = q == null || q.isBlank() ? null : q.trim().replaceFirst("(?i)^DEF-", "");
        String sortSql = SORTS.getOrDefault(sort, "d.number") + (order.equalsIgnoreCase("asc") ? " ASC" : " DESC");
        int limit = Math.min(Math.max(size, 1), 200);
        long total = db.sql("SELECT count(*) FROM defect d" + where).param("org", p.organizationId()).param("project", projectId).param("q", blankQ).param("status", status)
                .param("severity", severity).param("priority", priority).param("run", runId).query(Long.class).single();
        var items = db.sql(SELECT + where + " ORDER BY " + sortSql + ", d.number DESC LIMIT :limit OFFSET :offset").param("org", p.organizationId()).param("project", projectId).param("q", blankQ)
                .param("status", status).param("severity", severity).param("priority", priority).param("run", runId).param("limit", limit).param("offset", Math.max(page, 0) * limit)
                .query((rs, n) -> map(rs)).list();
        return new DefectPage(items, total);
    }

    private DefectView one(Principal p, UUID projectId, UUID id) {
        return db.sql(SELECT + " WHERE d.organization_id = ? AND d.project_id = ? AND d.id = ?").params(p.organizationId(), projectId, id)
                .query((rs, n) -> map(rs)).optional().orElseThrow(() -> new NotFoundException("Defect"));
    }

    /** The full chain: defect, the findings behind it, proof, reproduction steps, and linked test cases. */
    @GetMapping("/{defectId}")
    public Trace get(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID defectId) {
        access.require(p, projectId, Access.Level.VIEWER);
        DefectView defect = one(p, projectId, defectId);
        record Row(UUID id, UUID run, int attempt, int step) {}
        List<FindingTrace> findings = db.sql("""
                SELECT f.id, f.run_id, f.attempt, f.step FROM finding f JOIN test_run r ON r.organization_id = f.organization_id AND r.id = f.run_id
                WHERE f.organization_id = ? AND f.defect_id = ? ORDER BY r.created_at DESC LIMIT 20
                """).params(p.organizationId(), defectId).query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getInt(4))).list()
                .stream().map(r -> new FindingTrace(r.run(), r.attempt(), r.step(), evidence(p.organizationId(), r.id()), repro(p.organizationId(), r.run(), r.attempt(), r.step()))).toList();
        List<CaseLink> cases = db.sql("""
                SELECT c.id, c.title FROM test_case c JOIN defect_test_case l ON l.organization_id = c.organization_id AND l.test_case_id = c.id
                WHERE c.organization_id = ? AND l.defect_id = ? ORDER BY c.title
                """).params(p.organizationId(), defectId).query((rs, n) -> new CaseLink(rs.getObject(1, UUID.class), rs.getString(2))).list();
        return new Trace(defect, findings, cases);
    }

    private List<EvidenceLink> evidence(UUID org, UUID finding) {
        return db.sql("""
                SELECT e.id, e.type, e.sha256, e.size FROM evidence e
                JOIN finding_evidence fe ON fe.organization_id = e.organization_id AND fe.evidence_id = e.id
                WHERE e.organization_id = ? AND fe.finding_id = ? AND e.deleted_at IS NULL
                """).params(org, finding).query((rs, n) -> new EvidenceLink(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4))).list();
    }

    private List<ReproStep> repro(UUID org, UUID run, int attempt, int step) {
        return db.sql("""
                WITH RECURSIVE path AS (
                    SELECT sequence, parent, kind, url, label, 0 AS hop FROM run_step
                    WHERE organization_id = ? AND run_id = ? AND attempt = ? AND sequence = ?
                    UNION ALL
                    SELECT s.sequence, s.parent, s.kind, s.url, s.label, path.hop + 1 FROM run_step s
                    JOIN path ON s.sequence = path.parent
                    WHERE s.organization_id = ? AND s.run_id = ? AND s.attempt = ?
                )
                SELECT sequence, kind, url, label FROM path ORDER BY hop DESC
                """).params(org, run, attempt, step, org, run, attempt)
                .query((rs, n) -> new ReproStep(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DefectView create(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @Valid @RequestBody Create body) {
        access.require(p, projectId, Access.Level.TESTER);
        String severity = level(body.severity(), "MEDIUM");
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO defect (organization_id, id, project_id, fingerprint, type, severity, priority, title, url, detail, expected_result, module, source)
                VALUES (?,?,?,?, 'MANUAL', ?,?,?,?,?,?,?, 'MANUAL')
                """).params(p.organizationId(), id, projectId, "manual:" + id, severity, level(body.priority(), severity), body.title().trim(), text(body.url()), text(body.detail()),
                text(body.expectedResult()), text(body.module())).update();
        audit.record(p.organizationId(), projectId, p.email(), "DEFECT_CREATED", id.toString(), body.title());
        return one(p, projectId, id);
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    private static String level(String value, String fallback) {
        if (value == null) return fallback;
        if (!LEVELS.contains(value)) throw new ConflictException("Unknown level");
        return value;
    }

    @PatchMapping("/{defectId}")
    @Transactional
    public DefectView update(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID defectId, @RequestBody Update body) {
        access.require(p, projectId, Access.Level.QA_LEAD);
        DefectView d = one(p, projectId, defectId);
        String status = body.status() == null ? d.status() : body.status();
        if (!STATUSES.contains(status)) throw new ConflictException("Unknown status");
        db.sql("""
                UPDATE defect SET status = ?, priority = ?, severity = ?, module = ?, expected_result = ?, title = ?,
                       closed_at = CASE WHEN ? IN ('FIXED','WONT_FIX') THEN coalesce(closed_at, now()) ELSE NULL END
                WHERE organization_id = ? AND project_id = ? AND id = ?
                """).params(status, level(body.priority(), d.priority()), level(body.severity(), d.severity()), body.module() == null ? d.module() : body.module(),
                body.expectedResult() == null ? d.expectedResult() : body.expectedResult(), body.title() == null || body.title().isBlank() ? d.title() : body.title(), status,
                p.organizationId(), projectId, defectId).update();
        audit.record(p.organizationId(), projectId, p.email(), "DEFECT_UPDATED", defectId.toString(), status);
        return one(p, projectId, defectId);
    }
}
