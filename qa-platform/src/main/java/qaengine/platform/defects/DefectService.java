package qaengine.platform.defects;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Groups findings into defects by fingerprint, so repeated runs do not create duplicates. */
@Component
public class DefectService {

    public record Observed(UUID organizationId, UUID projectId, UUID runId, String fingerprint, String type, String severity,
                           String title, String url, String detail) {}

    private final JdbcClient db;

    public DefectService(JdbcClient db) {
        this.db = db;
    }

    /** A fixed defect that appears again reopens. */
    public UUID upsert(Observed o) {
        return db.sql("""
                INSERT INTO defect (organization_id, id, project_id, fingerprint, type, severity, title, url, detail, first_seen_run, last_seen_run)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (organization_id, project_id, fingerprint) DO UPDATE SET
                    occurrences = defect.occurrences + CASE WHEN defect.last_seen_run = EXCLUDED.last_seen_run THEN 0 ELSE 1 END,
                    last_seen_run = EXCLUDED.last_seen_run,
                    status = CASE WHEN defect.status = 'FIXED' THEN 'OPEN' ELSE defect.status END,
                    closed_at = CASE WHEN defect.status = 'FIXED' THEN NULL ELSE defect.closed_at END
                RETURNING id
                """).params(o.organizationId(), UUID.randomUUID(), o.projectId(), o.fingerprint(), o.type(), o.severity(), o.title(), o.url(),
                o.detail(), o.runId(), o.runId()).query(UUID.class).single();
    }
}
