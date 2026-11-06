package qaengine.platform.evidence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qaengine.core.evidence.EvidenceType;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

/** The only way to read evidence. Every read is authorized and audited. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/evidence")
public class EvidenceController {

    private final JdbcClient db;
    private final Access access;
    private final EvidenceStore store;
    private final AuditLog audit;

    public EvidenceController(JdbcClient db, Access access, EvidenceStore store, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.store = store;
        this.audit = audit;
    }

    public record EvidenceView(UUID id, UUID runId, int step, String type, String sha256, long size, OffsetDateTime createdAt, OffsetDateTime expiresAt, boolean legalHold, boolean inline) {}
    public record EvidencePage(List<EvidenceView> items, long total) {}

    @GetMapping
    public EvidencePage list(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @RequestParam(required = false) String type, @RequestParam(required = false) UUID runId,
                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        access.require(p, projectId, Access.Level.VIEWER);
        String where = """
                 FROM evidence e JOIN test_run r ON r.organization_id = e.organization_id AND r.id = e.run_id
                 WHERE e.organization_id = :org AND r.project_id = :project AND e.deleted_at IS NULL
                   AND (cast(:type as text) IS NULL OR e.type = cast(:type as text))
                   AND (cast(:run as uuid) IS NULL OR e.run_id = cast(:run as uuid))
                """;
        int limit = Math.min(Math.max(size, 1), 200);
        long total = db.sql("SELECT count(*)" + where).param("org", p.organizationId()).param("project", projectId).param("type", type).param("run", runId).query(Long.class).single();
        var items = db.sql("SELECT e.id, e.run_id, e.step, e.type, e.sha256, e.size, e.created_at, e.expires_at, e.legal_hold" + where + " ORDER BY e.created_at DESC, e.id LIMIT :limit OFFSET :offset")
                .param("org", p.organizationId()).param("project", projectId).param("type", type).param("run", runId).param("limit", limit).param("offset", Math.max(page, 0) * limit)
                .query((rs, n) -> new EvidenceView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getLong(6),
                        rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class), rs.getBoolean(9), !rs.getString(4).equals("DOM_SNAPSHOT"))).list();
        return new EvidencePage(items, total);
    }

    @GetMapping("/{evidenceId}/content")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID evidenceId) {
        access.require(p, projectId, Access.Level.VIEWER);
        record Row(String type, String key, String sha) {}
        Row row = db.sql("""
                SELECT e.type, e.storage_key, e.sha256 FROM evidence e
                JOIN test_run r ON r.organization_id = e.organization_id AND r.id = e.run_id
                WHERE e.organization_id = ? AND e.id = ? AND r.project_id = ? AND e.deleted_at IS NULL
                """).params(p.organizationId(), evidenceId, projectId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3))).optional()
                .orElseThrow(() -> new NotFoundException("Evidence"));
        audit.record(p.organizationId(), projectId, p.email(), "EVIDENCE_READ", evidenceId.toString(), row.type());

        EvidenceType type = EvidenceType.valueOf(row.type());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "sandbox; default-src 'none'");
        headers.setCacheControl("private, no-store");
        if (type == EvidenceType.SCREENSHOT) {
            headers.setContentType(MediaType.IMAGE_PNG);
        } else {
            // Target content is never rendered in our origin.
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDisposition(ContentDisposition.attachment().filename(row.sha() + "." + type.extension).build());
        }
        return ResponseEntity.ok().headers(headers).body(store.read(row.key()));
    }
}
