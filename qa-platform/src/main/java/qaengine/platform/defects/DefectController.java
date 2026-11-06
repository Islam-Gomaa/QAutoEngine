package qaengine.platform.defects;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/defects")
public class DefectController {

    public record DefectView(UUID id, String type, String severity, String title, String url, String detail, String status,
                             int occurrences, UUID firstSeenRun, UUID lastSeenRun, OffsetDateTime createdAt) {}
    public record Trace(DefectView defect, List<FindingTrace> findings, List<UUID> testCases) {}
    public record FindingTrace(UUID runId, int attempt, int step, List<EvidenceLink> evidence, List<ReproStep> reproduction) {}
    public record EvidenceLink(UUID id, String type, String sha256, long size) {}
    public record ReproStep(int sequence, String kind, String url, String label) {}
    public record StatusChange(String status) {}

    private static final Set<String> STATUSES = Set.of("OPEN", "TRIAGED", "FIXED", "WONT_FIX");
    private static final String COLUMNS = "id, type, severity, title, url, detail, status, occurrences, first_seen_run, last_seen_run, created_at";

    private final JdbcClient db;
    private final Access access;
    private final AuditLog audit;

    public DefectController(JdbcClient db, Access access, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.audit = audit;
    }

    private static DefectView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DefectView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getInt(8), rs.getObject(9, UUID.class), rs.getObject(10, UUID.class), rs.getObject(11, OffsetDateTime.class));
    }

    @GetMapping
    public List<DefectView> list(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.VIEWER);
        return db.sql("SELECT " + COLUMNS + " FROM defect WHERE organization_id = ? AND project_id = ? ORDER BY created_at DESC LIMIT 500")
                .params(p.organizationId(), projectId).query((rs, n) -> map(rs)).list();
    }

    /** The full chain: defect, the findings behind it, proof, reproduction steps, and linked test cases. */
    @GetMapping("/{defectId}")
    public Trace get(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID defectId) {
        access.require(p, projectId, Access.Level.VIEWER);
        DefectView defect = db.sql("SELECT " + COLUMNS + " FROM defect WHERE organization_id = ? AND project_id = ? AND id = ?")
                .params(p.organizationId(), projectId, defectId).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> new NotFoundException("Defect"));
        record Row(UUID id, UUID run, int attempt, int step) {}
        List<FindingTrace> findings = db.sql("SELECT id, run_id, attempt, step FROM finding WHERE organization_id = ? AND defect_id = ? ORDER BY run_id, attempt")
                .params(p.organizationId(), defectId).query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getInt(4))).list()
                .stream().map(r -> new FindingTrace(r.run(), r.attempt(), r.step(), evidence(p.organizationId(), r.id()), repro(p.organizationId(), r.run(), r.attempt(), r.step()))).toList();
        List<UUID> cases = db.sql("SELECT test_case_id FROM defect_test_case WHERE organization_id = ? AND defect_id = ?")
                .params(p.organizationId(), defectId).query(UUID.class).list();
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

    @PatchMapping("/{defectId}")
    @Transactional
    public void setStatus(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID defectId, @RequestBody StatusChange body) {
        access.require(p, projectId, Access.Level.QA_LEAD);
        if (!STATUSES.contains(body.status())) throw new ConflictException("Unknown status");
        int updated = db.sql("""
                UPDATE defect SET status = ?, closed_at = CASE WHEN ? IN ('FIXED','WONT_FIX') THEN now() ELSE NULL END
                WHERE organization_id = ? AND project_id = ? AND id = ?
                """).params(body.status(), body.status(), p.organizationId(), projectId, defectId).update();
        if (updated == 0) throw new NotFoundException("Defect");
        audit.record(p.organizationId(), p.email(), "DEFECT_STATUS", defectId.toString(), body.status());
    }
}
