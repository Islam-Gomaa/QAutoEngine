package qaengine.platform.evidence;

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
        audit.record(p.organizationId(), p.email(), "EVIDENCE_READ", evidenceId.toString(), row.type());

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
