package qaengine.platform.projects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
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
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    public record ProjectView(UUID id, String name, String description, OffsetDateTime archivedAt, String myRole, long testCases, long runs, long openDefects) {}
    public record CreateProject(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String description) {}
    public record UpdateProject(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String description) {}
    public record MemberRequest(UUID userId, String role) {}
    public record MemberView(UUID userId, String email, String role) {}

    private static final String SELECT = """
            SELECT pr.id, pr.name, pr.description, pr.archived_at,
                   (SELECT count(*) FROM test_case c WHERE c.organization_id = pr.organization_id AND c.project_id = pr.id AND c.archived_at IS NULL AND c.status NOT IN ('PENDING_REVIEW','REJECTED')),
                   (SELECT count(*) FROM test_run r WHERE r.organization_id = pr.organization_id AND r.project_id = pr.id),
                   (SELECT count(*) FROM defect d WHERE d.organization_id = pr.organization_id AND d.project_id = pr.id AND d.status IN ('OPEN','TRIAGED'))
            FROM project pr
            """;

    private final JdbcClient db;
    private final Access access;
    private final AuditLog audit;

    public ProjectController(JdbcClient db, Access access, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.audit = audit;
    }

    private ProjectView map(Principal p, java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = rs.getObject(1, UUID.class);
        return new ProjectView(id, rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class), access.roleIn(p, id), rs.getLong(5), rs.getLong(6), rs.getLong(7));
    }

    @GetMapping
    public List<ProjectView> list(@AuthenticationPrincipal Principal p, @RequestParam(defaultValue = "false") boolean archived) {
        return db.sql(SELECT + """
                WHERE pr.organization_id = :org AND (pr.archived_at IS NOT NULL) = :archived AND (:admin OR EXISTS (
                    SELECT 1 FROM project_member m
                    WHERE m.organization_id = pr.organization_id AND m.project_id = pr.id AND m.user_id = :user))
                ORDER BY pr.name
                """).param("org", p.organizationId()).param("archived", archived).param("admin", p.organizationAdmin()).param("user", p.userId())
                .query((rs, n) -> map(p, rs)).list();
    }

    @GetMapping("/{id}")
    public ProjectView get(@AuthenticationPrincipal Principal p, @PathVariable UUID id) {
        access.require(p, id, Access.Level.VIEWER);
        return db.sql(SELECT + " WHERE pr.organization_id = ? AND pr.id = ?").params(p.organizationId(), id).query((rs, n) -> map(p, rs)).single();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ProjectView create(@AuthenticationPrincipal Principal p, @Valid @RequestBody CreateProject body) {
        access.requireOrganizationAdmin(p);
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO project (organization_id, id, name, description) VALUES (?,?,?,?)")
                .params(p.organizationId(), id, body.name().trim(), body.description() == null ? "" : body.description()).update();
        audit.record(p.organizationId(), id, p.email(), "PROJECT_CREATED", id.toString(), body.name());
        return get(p, id);
    }

    @PatchMapping("/{id}")
    @Transactional
    public ProjectView update(@AuthenticationPrincipal Principal p, @PathVariable UUID id, @Valid @RequestBody UpdateProject body) {
        access.require(p, id, Access.Level.PROJECT_ADMIN);
        db.sql("UPDATE project SET name = ?, description = ? WHERE organization_id = ? AND id = ?")
                .params(body.name().trim(), body.description() == null ? "" : body.description(), p.organizationId(), id).update();
        audit.record(p.organizationId(), id, p.email(), "PROJECT_UPDATED", id.toString(), body.name());
        return get(p, id);
    }

    /** Nothing is deleted: history, evidence and defects stay, and the project becomes read-only. */
    @PostMapping("/{id}/archive")
    @Transactional
    public void archive(@AuthenticationPrincipal Principal p, @PathVariable UUID id) {
        access.requireOrganizationAdmin(p);
        access.require(p, id, Access.Level.VIEWER);
        db.sql("UPDATE project SET archived_at = now() WHERE organization_id = ? AND id = ? AND archived_at IS NULL").params(p.organizationId(), id).update();
        audit.record(p.organizationId(), id, p.email(), "PROJECT_ARCHIVED", id.toString(), "");
    }

    @PostMapping("/{id}/restore")
    @Transactional
    public void restore(@AuthenticationPrincipal Principal p, @PathVariable UUID id) {
        access.requireOrganizationAdmin(p);
        access.require(p, id, Access.Level.VIEWER);
        db.sql("UPDATE project SET archived_at = NULL WHERE organization_id = ? AND id = ?").params(p.organizationId(), id).update();
        audit.record(p.organizationId(), id, p.email(), "PROJECT_RESTORED", id.toString(), "");
    }

    @GetMapping("/{id}/members")
    public List<MemberView> members(@AuthenticationPrincipal Principal p, @PathVariable UUID id) {
        access.require(p, id, Access.Level.VIEWER);
        return db.sql("""
                SELECT u.id, u.email, m.role FROM project_member m JOIN app_user u ON u.id = m.user_id
                WHERE m.organization_id = ? AND m.project_id = ? ORDER BY u.email
                """).params(p.organizationId(), id).query((rs, n) -> new MemberView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
    }

    @PutMapping("/{id}/members")
    @Transactional
    public void setMember(@AuthenticationPrincipal Principal p, @PathVariable UUID id, @RequestBody MemberRequest body) {
        access.require(p, id, Access.Level.PROJECT_ADMIN);
        Access.Level.valueOf(body.role());
        boolean inOrganization = db.sql("SELECT count(*) FROM membership WHERE organization_id = ? AND user_id = ?")
                .params(p.organizationId(), body.userId()).query(Long.class).single() == 1;
        if (!inOrganization) throw new NotFoundException("User");
        db.sql("""
                INSERT INTO project_member (organization_id, project_id, user_id, role) VALUES (?,?,?,?)
                ON CONFLICT (organization_id, project_id, user_id) DO UPDATE SET role = EXCLUDED.role
                """).params(p.organizationId(), id, body.userId(), body.role()).update();
        audit.record(p.organizationId(), id, p.email(), "PROJECT_MEMBER_SET", id.toString(), body.userId() + " " + body.role());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @Transactional
    public void removeMember(@AuthenticationPrincipal Principal p, @PathVariable UUID id, @PathVariable UUID userId) {
        access.require(p, id, Access.Level.PROJECT_ADMIN);
        int removed = db.sql("DELETE FROM project_member WHERE organization_id = ? AND project_id = ? AND user_id = ?")
                .params(p.organizationId(), id, userId).update();
        if (removed == 0) throw new NotFoundException("Member");
        audit.record(p.organizationId(), id, p.email(), "PROJECT_MEMBER_REMOVED", id.toString(), userId.toString());
    }
}
