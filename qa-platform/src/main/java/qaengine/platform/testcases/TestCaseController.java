package qaengine.platform.testcases;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/test-cases")
public class TestCaseController {

    public record ManualStep(@NotBlank String action, String expected) {}
    public record CreateCase(@NotBlank @Size(max = 240) String title, String description, UUID suiteId, String priority, String status, List<String> tags, String module, List<ManualStep> steps) {}
    public record UpdateCase(@NotBlank @Size(max = 240) String title, String description, UUID suiteId, String priority, String status, List<String> tags, String module) {}
    public record CaseView(UUID id, String title, String description, UUID suiteId, String suiteName, String source, String priority, String status, List<String> tags, String module,
                           int latestVersion, String lastVerdict, OffsetDateTime archivedAt) {}
    public record CasePage(List<CaseView> items, long total) {}
    public record VersionView(int version, String steps, String contentHash) {}
    public record HistoryRow(String kind, String verdict, OffsetDateTime at, UUID runId, String notes, String by) {}
    public record LinkedDefect(UUID id, long number, String title, String severity, String status) {}
    public record CaseDetail(CaseView testCase, String steps, List<VersionView> versions, List<HistoryRow> history, List<LinkedDefect> defects) {}
    public record RecordResult(String verdict, String notes) {}
    public record LinkDefect(UUID defectId) {}

    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> STATUSES = Set.of("DRAFT", "READY", "DEPRECATED");

    private static final String SELECT = """
            SELECT c.id, c.title, c.description, c.suite_id, s.name, c.source, c.priority, c.status, c.tags, c.module,
                   coalesce((SELECT max(v.version) FROM test_case_version v WHERE v.organization_id = c.organization_id AND v.test_case_id = c.id), 0),
                   coalesce((SELECT r.verdict FROM test_result r
                             JOIN test_case_version rv ON rv.organization_id = r.organization_id AND rv.id = r.test_case_version_id
                             JOIN test_run tr ON tr.organization_id = r.organization_id AND tr.id = r.run_id
                             WHERE rv.organization_id = c.organization_id AND rv.test_case_id = c.id ORDER BY tr.created_at DESC LIMIT 1),
                            (SELECT m.verdict FROM manual_result m WHERE m.organization_id = c.organization_id AND m.test_case_id = c.id ORDER BY m.recorded_at DESC LIMIT 1)),
                   c.archived_at
            FROM test_case c
            LEFT JOIN test_suite s ON s.organization_id = c.organization_id AND s.id = c.suite_id
            """;

    private final JdbcClient db;
    private final Access access;
    private final TestCaseService service;
    private final AuditLog audit;
    private final ObjectMapper json;

    public TestCaseController(JdbcClient db, Access access, TestCaseService service, AuditLog audit, ObjectMapper json) {
        this.db = db;
        this.access = access;
        this.service = service;
        this.audit = audit;
        this.json = json;
    }

    private static CaseView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CaseView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                Arrays.asList((String[]) rs.getArray(9).getArray()), rs.getString(10), rs.getInt(11), rs.getString(12), rs.getObject(13, OffsetDateTime.class));
    }

    @GetMapping
    public CasePage list(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @RequestParam(required = false) String q, @RequestParam(required = false) UUID suiteId,
                         @RequestParam(required = false) String source, @RequestParam(required = false) String priority, @RequestParam(required = false) String status,
                         @RequestParam(defaultValue = "false") boolean archived, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        access.require(p, projectId, Access.Level.VIEWER);
        String where = """
                 WHERE c.organization_id = :org AND c.project_id = :project AND (c.archived_at IS NOT NULL) = :archived
                   AND (cast(:q as text) IS NULL OR c.title ILIKE '%' || cast(:q as text) || '%' OR c.module ILIKE '%' || cast(:q as text) || '%' OR cast(:q as text) = ANY(c.tags))
                   AND (cast(:suite as uuid) IS NULL OR c.suite_id = cast(:suite as uuid))
                   AND (cast(:source as text) IS NULL OR c.source = cast(:source as text))
                   AND (cast(:priority as text) IS NULL OR c.priority = cast(:priority as text))
                   AND (cast(:status as text) IS NULL OR c.status = cast(:status as text))
                """;
        String blankQ = q == null || q.isBlank() ? null : q.trim();
        int limit = Math.min(Math.max(size, 1), 200);
        long total = db.sql("SELECT count(*) FROM test_case c" + where).param("org", p.organizationId()).param("project", projectId).param("archived", archived).param("q", blankQ)
                .param("suite", suiteId).param("source", source).param("priority", priority).param("status", status).query(Long.class).single();
        var items = db.sql(SELECT + where + " ORDER BY c.source DESC, c.title LIMIT :limit OFFSET :offset").param("org", p.organizationId()).param("project", projectId).param("archived", archived)
                .param("q", blankQ).param("suite", suiteId).param("source", source).param("priority", priority).param("status", status).param("limit", limit).param("offset", Math.max(page, 0) * limit)
                .query((rs, n) -> map(rs)).list();
        return new CasePage(items, total);
    }

    private CaseView view(Principal p, UUID projectId, UUID id) {
        return db.sql(SELECT + " WHERE c.organization_id = ? AND c.project_id = ? AND c.id = ?").params(p.organizationId(), projectId, id)
                .query((rs, n) -> map(rs)).optional().orElseThrow(() -> new NotFoundException("Test case"));
    }

    @GetMapping("/{caseId}")
    public CaseDetail get(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID caseId) {
        access.require(p, projectId, Access.Level.VIEWER);
        CaseView c = view(p, projectId, caseId);
        List<VersionView> versions = versions(p, projectId, caseId);
        List<HistoryRow> history = db.sql("""
                SELECT 'AUTOMATED', r.verdict, tr.created_at, r.run_id, '', '' FROM test_result r
                JOIN test_case_version v ON v.organization_id = r.organization_id AND v.id = r.test_case_version_id
                JOIN test_run tr ON tr.organization_id = r.organization_id AND tr.id = r.run_id
                WHERE v.organization_id = :org AND v.test_case_id = :id
                UNION ALL
                SELECT 'MANUAL', m.verdict, m.recorded_at, NULL, m.notes, u.email FROM manual_result m JOIN app_user u ON u.id = m.recorded_by
                WHERE m.organization_id = :org AND m.test_case_id = :id
                ORDER BY 3 DESC LIMIT 100
                """).param("org", p.organizationId()).param("id", caseId).query((rs, n) -> new HistoryRow(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6))).list();
        List<LinkedDefect> defects = db.sql("""
                SELECT d.id, d.number, d.title, d.severity, d.status FROM defect d
                JOIN defect_test_case l ON l.organization_id = d.organization_id AND l.defect_id = d.id
                WHERE d.organization_id = ? AND l.test_case_id = ? ORDER BY d.number DESC
                """).params(p.organizationId(), caseId).query((rs, n) -> new LinkedDefect(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5))).list();
        return new CaseDetail(c, versions.isEmpty() ? "[]" : versions.getFirst().steps(), versions, history, defects);
    }

    private void checkSuite(Principal p, UUID projectId, UUID suiteId) {
        if (suiteId == null) return;
        boolean ok = db.sql("SELECT count(*) FROM test_suite WHERE organization_id = ? AND project_id = ? AND id = ?").params(p.organizationId(), projectId, suiteId).query(Long.class).single() == 1;
        if (!ok) throw new NotFoundException("Suite");
    }

    private static void checkFields(String priority, String status) {
        if (priority != null && !PRIORITIES.contains(priority)) throw new ConflictException("Unknown priority");
        if (status != null && !STATUSES.contains(status)) throw new ConflictException("Unknown status");
    }

    /** Created here means manual: automated cases come from runs. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CaseView create(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @Valid @RequestBody CreateCase body) {
        access.require(p, projectId, Access.Level.TESTER);
        checkSuite(p, projectId, body.suiteId());
        checkFields(body.priority(), body.status());
        var saved = service.save(p.organizationId(), projectId, "manual:" + UUID.randomUUID(), body.title().trim(), json.writeValueAsString(body.steps() == null ? List.of() : body.steps()));
        db.sql("""
                UPDATE test_case SET source = 'MANUAL', description = ?, suite_id = ?, priority = ?, status = ?, tags = ?, module = ?
                WHERE organization_id = ? AND id = ?
                """).params(text(body.description()), body.suiteId(), body.priority() == null ? "MEDIUM" : body.priority(), body.status() == null ? "DRAFT" : body.status(),
                (body.tags() == null ? List.<String>of() : body.tags()).toArray(String[]::new), text(body.module()), p.organizationId(), saved.testCaseId()).update();
        audit.record(p.organizationId(), projectId, p.email(), "TEST_CASE_CREATED", saved.testCaseId().toString(), body.title());
        return view(p, projectId, saved.testCaseId());
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    @PatchMapping("/{caseId}")
    @Transactional
    public CaseView update(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID caseId, @Valid @RequestBody UpdateCase body) {
        access.require(p, projectId, Access.Level.TESTER);
        CaseView current = view(p, projectId, caseId);
        checkSuite(p, projectId, body.suiteId());
        checkFields(body.priority(), body.status());
        db.sql("UPDATE test_case SET title = ?, description = ?, suite_id = ?, priority = ?, status = ?, tags = ?, module = ? WHERE organization_id = ? AND id = ?")
                .params(body.title().trim(), text(body.description()), body.suiteId(), body.priority() == null ? current.priority() : body.priority(), body.status() == null ? current.status() : body.status(),
                        (body.tags() == null ? List.<String>of() : body.tags()).toArray(String[]::new), text(body.module()), p.organizationId(), caseId).update();
        audit.record(p.organizationId(), projectId, p.email(), "TEST_CASE_UPDATED", caseId.toString(), body.title());
        return view(p, projectId, caseId);
    }

    /** Recorded steps of automated cases stay as recorded. Manual steps create a new version. */
    @PutMapping("/{caseId}/steps")
    @Transactional
    public CaseDetail saveSteps(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID caseId, @Valid @RequestBody List<ManualStep> steps) {
        access.require(p, projectId, Access.Level.TESTER);
        CaseView c = view(p, projectId, caseId);
        if (!c.source().equals("MANUAL")) throw new ConflictException("Automated steps come from runs");
        String key = db.sql("SELECT natural_key FROM test_case WHERE organization_id = ? AND id = ?").params(p.organizationId(), caseId).query(String.class).single();
        service.save(p.organizationId(), projectId, key, c.title(), json.writeValueAsString(steps));
        audit.record(p.organizationId(), projectId, p.email(), "TEST_CASE_STEPS_UPDATED", caseId.toString(), "");
        return get(p, projectId, caseId);
    }

    @PostMapping("/{caseId}/archive")
    @Transactional
    public void archive(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID caseId) {
        access.require(p, projectId, Access.Level.TESTER);
        view(p, projectId, caseId);
        db.sql("UPDATE test_case SET archived_at = now() WHERE organization_id = ? AND id = ?").params(p.organizationId(), caseId).update();
        audit.record(p.organizationId(), projectId, p.email(), "TEST_CASE_ARCHIVED", caseId.toString(), "");
    }

    @PostMapping("/{caseId}/restore")
    @Transactional
    public void restore(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID caseId) {
        access.require(p, projectId, Access.Level.TESTER);
        view(p, projectId, caseId);
        db.sql("UPDATE test_case SET archived_at = NULL WHERE organization_id = ? AND id = ?").params(p.organizationId(), caseId).update();
        audit.record(p.organizationId(), projectId, p.email(), "TEST_CASE_RESTORED", caseId.toString(), "");
    }

    @PostMapping("/{caseId}/results")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public void recordResult(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID caseId, @RequestBody RecordResult body) {
        access.require(p, projectId, Access.Level.TESTER);
        CaseView c = view(p, projectId, caseId);
        if (!Set.of("PASS", "FAIL", "BLOCKED").contains(String.valueOf(body.verdict()))) throw new ConflictException("Unknown verdict");
        db.sql("INSERT INTO manual_result (organization_id, id, test_case_id, version, verdict, notes, recorded_by) VALUES (?,?,?,?,?,?,?)")
                .params(p.organizationId(), UUID.randomUUID(), caseId, c.latestVersion(), body.verdict(), text(body.notes()), p.userId()).update();
        audit.record(p.organizationId(), projectId, p.email(), "TEST_RESULT_RECORDED", caseId.toString(), body.verdict());
    }

    @PostMapping("/{caseId}/defects")
    @Transactional
    public void linkDefect(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID caseId, @RequestBody LinkDefect body) {
        access.require(p, projectId, Access.Level.TESTER);
        view(p, projectId, caseId);
        boolean ok = db.sql("SELECT count(*) FROM defect WHERE organization_id = ? AND project_id = ? AND id = ?").params(p.organizationId(), projectId, body.defectId()).query(Long.class).single() == 1;
        if (!ok) throw new NotFoundException("Defect");
        service.link(p.organizationId(), body.defectId(), caseId);
        audit.record(p.organizationId(), projectId, p.email(), "DEFECT_LINKED", caseId.toString(), body.defectId().toString());
    }

    @DeleteMapping("/{caseId}/defects/{defectId}")
    @Transactional
    public void unlinkDefect(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID caseId, @PathVariable UUID defectId) {
        access.require(p, projectId, Access.Level.TESTER);
        view(p, projectId, caseId);
        db.sql("DELETE FROM defect_test_case WHERE organization_id = ? AND defect_id = ? AND test_case_id = ?").params(p.organizationId(), defectId, caseId).update();
        audit.record(p.organizationId(), projectId, p.email(), "DEFECT_UNLINKED", caseId.toString(), defectId.toString());
    }

    @GetMapping("/{caseId}/versions")
    public List<VersionView> versions(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID caseId) {
        access.require(p, projectId, Access.Level.VIEWER);
        view(p, projectId, caseId);
        return db.sql("SELECT version, steps::text, content_hash FROM test_case_version WHERE organization_id = ? AND test_case_id = ? ORDER BY version DESC")
                .params(p.organizationId(), caseId).query((rs, n) -> new VersionView(rs.getInt(1), rs.getString(2), rs.getString(3))).list();
    }
}
