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
        Optional<Boolean> archived = db.sql("SELECT archived_at IS NOT NULL FROM project WHERE organization_id = ? AND id = ?")
                .params(p.organizationId(), projectId).query(Boolean.class).optional();
        if (archived.isEmpty()) throw new NotFoundException("Project");
        allowed(p, projectId, needed);
        if (archived.get() && needed != Level.VIEWER) throw new qaengine.platform.shared.ConflictException("This project is archived");
    }

    private void allowed(Principal p, UUID projectId, Level needed) {
        if (p.organizationAdmin()) return;
        Optional<String> role = db.sql("SELECT role FROM project_member WHERE organization_id = ? AND project_id = ? AND user_id = ?")
                .params(p.organizationId(), projectId, p.userId()).query(String.class).optional();
        if (role.isEmpty()) throw new NotFoundException("Project");
        if (Level.valueOf(role.get()).ordinal() < needed.ordinal()) throw new AccessDeniedException("Insufficient role");
    }

    /** Projects a query may read. Bind :org, :admin, :user and :project (null means every visible project). */
    public static final String VISIBLE_PROJECTS = """
            (SELECT vp.id FROM project vp WHERE vp.organization_id = :org AND vp.archived_at IS NULL
               AND (cast(:project as uuid) IS NULL OR vp.id = cast(:project as uuid))
               AND (:admin OR EXISTS (SELECT 1 FROM project_member vm WHERE vm.organization_id = vp.organization_id AND vm.project_id = vp.id AND vm.user_id = :user)))
            """;

    /** Reads the caller's role, for showing what they can do. The server still checks every action. */
    public String roleIn(Principal p, UUID projectId) {
        if (p.organizationAdmin()) return "PROJECT_ADMIN";
        return db.sql("SELECT role FROM project_member WHERE organization_id = ? AND project_id = ? AND user_id = ?")
                .params(p.organizationId(), projectId, p.userId()).query(String.class).optional().orElse(null);
    }

    public void requireOrganizationAdmin(Principal p) {
        if (!p.organizationAdmin()) throw new AccessDeniedException("Organization Admin required");
    }
}
