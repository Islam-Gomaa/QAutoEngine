package qaengine.platform.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

/**
 * Project-level authorization. A project the caller cannot see answers 404, not 403, so neither
 * other projects nor other organizations are revealed.
 */
@Component
public class Access {

    public enum Level { VIEWER, TESTER, QA_LEAD, PROJECT_ADMIN }

    private final JdbcClient db;

    public Access(JdbcClient db) {
        this.db = db;
    }

    public void require(Principal p, UUID projectId, Level needed) {
        boolean exists = db.sql("SELECT count(*) FROM project WHERE organization_id = ? AND id = ?")
                .params(p.organizationId(), projectId).query(Long.class).single() == 1;
        if (!exists) throw new NotFoundException("Project");
        if (p.organizationAdmin()) return;
        Optional<String> role = db.sql("SELECT role FROM project_member WHERE organization_id = ? AND project_id = ? AND user_id = ?")
                .params(p.organizationId(), projectId, p.userId()).query(String.class).optional();
        if (role.isEmpty()) throw new NotFoundException("Project");
        if (Level.valueOf(role.get()).ordinal() < needed.ordinal()) throw new AccessDeniedException("Insufficient role");
    }

    public void requireOrganizationAdmin(Principal p) {
        if (!p.organizationAdmin()) throw new AccessDeniedException("Organization Admin required");
    }
}
