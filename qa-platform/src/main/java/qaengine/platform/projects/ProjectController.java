package qaengine.platform.projects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.Principal;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    public record ProjectView(UUID id, String name) {}
    public record CreateProject(@NotBlank String name) {}
    public record MemberRequest(UUID userId, String role) {}

    private final JdbcClient db;
    private final Access access;
    private final AuditLog audit;

    public ProjectController(JdbcClient db, Access access, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping
    public List<ProjectView> list(@AuthenticationPrincipal Principal p) {
        return db.sql("""
                SELECT pr.id, pr.name FROM project pr
                WHERE pr.organization_id = :org AND (:admin OR EXISTS (
                    SELECT 1 FROM project_member m
                    WHERE m.organization_id = pr.organization_id AND m.project_id = pr.id AND m.user_id = :user))
                ORDER BY pr.name
                """).param("org", p.organizationId()).param("admin", p.organizationAdmin()).param("user", p.userId())
                .query((rs, n) -> new ProjectView(rs.getObject(1, UUID.class), rs.getString(2))).list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ProjectView create(@AuthenticationPrincipal Principal p, @Valid @RequestBody CreateProject body) {
        access.requireOrganizationAdmin(p);
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO project (organization_id, id, name) VALUES (?,?,?)").params(p.organizationId(), id, body.name()).update();
        audit.record(p.organizationId(), p.email(), "PROJECT_CREATED", id.toString(), "");
        return new ProjectView(id, body.name());
    }

    @PutMapping("/{id}/members")
    @Transactional
    public void setMember(@AuthenticationPrincipal Principal p, @PathVariable UUID id, @RequestBody MemberRequest body) {
        access.require(p, id, Access.Level.PROJECT_ADMIN);
        Access.Level.valueOf(body.role());
        boolean inOrganization = db.sql("SELECT count(*) FROM membership WHERE organization_id = ? AND user_id = ?")
                .params(p.organizationId(), body.userId()).query(Long.class).single() == 1;
        if (!inOrganization) throw new qaengine.platform.shared.NotFoundException("User");
        db.sql("""
                INSERT INTO project_member (organization_id, project_id, user_id, role) VALUES (?,?,?,?)
                ON CONFLICT (organization_id, project_id, user_id) DO UPDATE SET role = EXCLUDED.role
                """).params(p.organizationId(), id, body.userId(), body.role()).update();
        audit.record(p.organizationId(), p.email(), "PROJECT_MEMBER_SET", id.toString(), body.userId() + " " + body.role());
    }
}
