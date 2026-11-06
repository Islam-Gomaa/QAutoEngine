package qaengine.platform.targets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.Principal;

/** Targets and their environments. An environment always gets a READ_ONLY policy on creation. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class TargetController {

    public record CreateTarget(@NotBlank String name) {}
    public record TargetView(UUID id, String name) {}
    public record CreateEnvironment(@NotBlank String name, @NotBlank String classification, @NotBlank String baseUrl,
                                    List<String> includePaths, List<String> excludePaths, String authorizedBy) {}
    public record EnvironmentView(UUID id, UUID targetId, String name, String classification, String baseUrl, String mode) {}

    private static final Set<String> CLASSES = Set.of("LOCAL", "DEV", "STAGING", "PRODUCTION");

    private final JdbcClient db;
    private final Access access;
    private final AuditLog audit;

    public TargetController(JdbcClient db, Access access, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping("/targets")
    public List<TargetView> targets(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.VIEWER);
        return db.sql("SELECT id, name FROM target WHERE organization_id = ? AND project_id = ? ORDER BY name")
                .params(p.organizationId(), projectId).query((rs, n) -> new TargetView(rs.getObject(1, UUID.class), rs.getString(2))).list();
    }

    @PostMapping("/targets")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TargetView createTarget(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @Valid @RequestBody CreateTarget body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO target (organization_id, id, project_id, name) VALUES (?,?,?,?)").params(p.organizationId(), id, projectId, body.name()).update();
        audit.record(p.organizationId(), p.email(), "TARGET_CREATED", id.toString(), body.name());
        return new TargetView(id, body.name());
    }

    @GetMapping("/environments")
    public List<EnvironmentView> environments(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.VIEWER);
        return db.sql("""
                SELECT e.id, e.target_id, e.name, e.classification, e.base_url, ep.mode
                FROM environment e
                JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                JOIN execution_policy ep ON ep.organization_id = e.organization_id AND ep.environment_id = e.id
                WHERE e.organization_id = ? AND t.project_id = ? ORDER BY e.name
                """).params(p.organizationId(), projectId).query((rs, n) -> new EnvironmentView(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))).list();
    }

    @PostMapping("/targets/{targetId}/environments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public EnvironmentView createEnvironment(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID targetId,
                                             @Valid @RequestBody CreateEnvironment body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        boolean targetInProject = db.sql("SELECT count(*) FROM target WHERE organization_id = ? AND id = ? AND project_id = ?")
                .params(p.organizationId(), targetId, projectId).query(Long.class).single() == 1;
        if (!targetInProject) throw new qaengine.platform.shared.NotFoundException("Target");
        if (!CLASSES.contains(body.classification())) throw new ConflictException("Unknown environment class");
        URI uri = URI.create(body.baseUrl());
        if (uri.getHost() == null || !List.of("http", "https").contains(String.valueOf(uri.getScheme()))) throw new ConflictException("Base URL must be http or https");
        List<String> include = body.includePaths() == null ? List.of() : body.includePaths();
        if (body.classification().equals("PRODUCTION") && include.isEmpty()) throw new ConflictException("Production needs explicit include paths");

        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO environment (organization_id, id, target_id, name, classification, base_url, authorized_by, authorized_at)
                VALUES (?,?,?,?,?,?,?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END)
                """).params(p.organizationId(), id, targetId, body.name(), body.classification(), body.baseUrl(), body.authorizedBy(), body.authorizedBy()).update();
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        db.sql("INSERT INTO allowed_host (organization_id, environment_id, origin) VALUES (?,?,?)").params(p.organizationId(), id, origin).update();
        db.sql("INSERT INTO execution_policy (organization_id, environment_id, include_paths, exclude_paths) VALUES (?,?,?,?)")
                .params(p.organizationId(), id, include.toArray(String[]::new),
                        (body.excludePaths() == null ? List.<String>of() : body.excludePaths()).toArray(String[]::new)).update();
        audit.record(p.organizationId(), p.email(), "ENVIRONMENT_CREATED", id.toString(), body.classification() + " " + origin);
        return new EnvironmentView(id, targetId, body.name(), body.classification(), body.baseUrl(), "READ_ONLY");
    }
}
