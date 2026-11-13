package qaengine.platform.audit;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Writes in the caller's transaction, so an action and its audit record commit together. */
@Component
public class AuditLog {

    private final JdbcClient db;

    public AuditLog(JdbcClient db) {
        this.db = db;
    }

    public void record(UUID organizationId, String actor, String action, String object, String detail) {
        record(organizationId, null, actor, action, object, detail);
    }

    public void record(UUID organizationId, UUID projectId, String actor, String action, String object, String detail) {
        db.sql("INSERT INTO audit_event (organization_id, project_id, actor, action, object, detail) VALUES (?,?,?,?,?,?)")
                .params(organizationId, projectId, actor, action, object, detail).update();
    }
}
