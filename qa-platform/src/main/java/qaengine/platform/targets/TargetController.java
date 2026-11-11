package qaengine.platform.targets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qaengine.core.policy.BaseUrl;
import qaengine.core.policy.ExecutionLock;
import qaengine.core.policy.PolicyException;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

/**
 * Applications and their environments. An environment always gets a READ_ONLY policy on creation.
 * Inactive is a reversible switch that stops new runs; archive is permanent and keeps history.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class TargetController {

    /** @param environments created in the same transaction, so a refused environment leaves no half-made application */
    public record CreateTarget(@NotBlank String name, String type, String description, Boolean active, List<@Valid CreateEnvironment> environments) {}
    public record UpdateTarget(@NotBlank String name, String description) {}
    public record TargetView(UUID id, String name, String type, String description, boolean active, OffsetDateTime archivedAt) {}
    public record CreateEnvironment(@NotBlank String name, @NotBlank String classification, @NotBlank String baseUrl,
                                    List<String> includePaths, List<String> excludePaths, String authorizedBy,
                                    String channel, String apiSpecPath, Integer apiSlowMillis, List<String> apiSeedPaths, String description, Boolean active) {}
    public record UpdateEnvironment(@NotBlank String name, String description, List<String> includePaths, List<String> excludePaths,
                                    String apiSpecPath, Integer apiSlowMillis) {}
    /**
     * @param executable false for anything but the fixture until the security gate passes
     * @param applicationActive an inactive application stops its environments from running, whatever their own switch says
     */
    public record EnvironmentView(UUID id, UUID targetId, String targetName, String name, String classification, String baseUrl, String mode, String channel,
                                  String description, boolean active, boolean applicationActive, boolean executable, List<String> includePaths, List<String> excludePaths,
                                  String apiSpecPath, int apiSlowMillis, String loginMethod, String credentialName, String lastRunStatus, OffsetDateTime lastRunAt) {}
    public record BaseUrlCheck(String baseUrl) {}
    /** What would be stored. Nothing is contacted: the platform never reaches a target itself. */
    public record BaseUrlResult(String baseUrl, String origin, boolean executable) {}

    private static final Set<String> CLASSES = Set.of("LOCAL", "DEV", "QA", "UAT", "STAGING", "PRODUCTION");
    private static final Set<String> TYPES = Set.of("WEB", "API");
    private static final String RELATIVE = "/[A-Za-z0-9._~/-]*";

    private final JdbcClient db;
    private final Access access;
    private final AuditLog audit;

    public TargetController(JdbcClient db, Access access, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.audit = audit;
    }

    private static final String TARGET = "SELECT id, name, type, description, active, archived_at FROM target ";

    private static TargetView target(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TargetView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getObject(6, OffsetDateTime.class));
    }

    @GetMapping("/targets")
    public List<TargetView> targets(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.VIEWER);
        return db.sql(TARGET + "WHERE organization_id = ? AND project_id = ? AND archived_at IS NULL ORDER BY name")
                .params(p.organizationId(), projectId).query((rs, n) -> target(rs)).list();
    }

    @PostMapping("/targets")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TargetView createTarget(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @Valid @RequestBody CreateTarget body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        String type = body.type() == null ? "WEB" : body.type();
        if (!TYPES.contains(type)) throw new ConflictException("Unsupported application type");
        UUID id = UUID.randomUUID();
        String description = body.description() == null ? "" : body.description();
        boolean active = body.active() == null || body.active();
        db.sql("INSERT INTO target (organization_id, id, project_id, name, type, description, active) VALUES (?,?,?,?,?,?,?)")
                .params(p.organizationId(), id, projectId, body.name(), type, description, active).update();
        audit.record(p.organizationId(), projectId, p.email(), "APPLICATION_CREATED", id.toString(), body.name());
        for (CreateEnvironment environment : body.environments() == null ? List.<CreateEnvironment>of() : body.environments()) {
            insertEnvironment(p, projectId, id, type, environment);
        }
        return new TargetView(id, body.name(), type, description, active, null);
    }

    /** Validates and normalizes a base URL the way saving would, so a form can show the result before saving. */
    @PostMapping("/base-url-check")
    public BaseUrlResult checkBaseUrl(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @RequestBody BaseUrlCheck body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        String url = normalized(body.baseUrl());
        return new BaseUrlResult(url, BaseUrl.origin(url), ExecutionLock.isLocal(url));
    }

    private static String normalized(String raw) {
        try {
            return BaseUrl.normalize(raw);
        } catch (PolicyException e) {
            // BaseUrl's messages are fixed text and never repeat the input.
            throw new ConflictException(e.getMessage());
        }
    }

    @PostMapping("/targets/{targetId}/{change:activate|deactivate}")
    @Transactional
    public TargetView setTargetActive(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID targetId, @PathVariable String change) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        requireTarget(p, projectId, targetId);
        boolean active = change.equals("activate");
        int updated = db.sql("UPDATE target SET active = ? WHERE organization_id = ? AND id = ? AND archived_at IS NULL").params(active, p.organizationId(), targetId).update();
        if (updated == 0) throw new ConflictException("This application is archived");
        audit.record(p.organizationId(), projectId, p.email(), active ? "APPLICATION_ACTIVATED" : "APPLICATION_DEACTIVATED", targetId.toString(), "");
        return db.sql(TARGET + "WHERE organization_id = ? AND id = ?").params(p.organizationId(), targetId).query((rs, n) -> target(rs)).single();
    }

    private void requireTarget(Principal p, UUID projectId, UUID targetId) {
        boolean ok = db.sql("SELECT count(*) FROM target WHERE organization_id = ? AND id = ? AND project_id = ?")
                .params(p.organizationId(), targetId, projectId).query(Long.class).single() == 1;
        if (!ok) throw new NotFoundException("Application");
    }

    @PatchMapping("/targets/{targetId}")
    @Transactional
    public void updateTarget(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID targetId, @Valid @RequestBody UpdateTarget body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        requireTarget(p, projectId, targetId);
        db.sql("UPDATE target SET name = ?, description = ? WHERE organization_id = ? AND id = ?")
                .params(body.name(), body.description() == null ? "" : body.description(), p.organizationId(), targetId).update();
        audit.record(p.organizationId(), projectId, p.email(), "APPLICATION_UPDATED", targetId.toString(), body.name());
    }

    @PostMapping("/targets/{targetId}/archive")
    @Transactional
    public void archiveTarget(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID targetId) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        requireTarget(p, projectId, targetId);
        db.sql("UPDATE target SET archived_at = now() WHERE organization_id = ? AND id = ?").params(p.organizationId(), targetId).update();
        db.sql("UPDATE environment SET archived_at = now() WHERE organization_id = ? AND target_id = ? AND archived_at IS NULL").params(p.organizationId(), targetId).update();
        audit.record(p.organizationId(), projectId, p.email(), "APPLICATION_ARCHIVED", targetId.toString(), "");
    }

    private static List<String> array(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        return Arrays.asList((String[]) rs.getArray(column).getArray());
    }

    private static final String ENV = """
            SELECT e.id, e.target_id, t.name, e.name, e.classification, e.base_url, ep.mode, e.channel, e.description, ep.include_paths, ep.exclude_paths,
                   e.api_spec_path, e.api_slow_ms, e.login_method,
                   (SELECT c.name FROM credential c WHERE c.organization_id = e.organization_id AND c.id = e.login_credential_id),
                   lr.status, lr.created_at, e.active, t.active
            FROM environment e
            JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
            JOIN execution_policy ep ON ep.organization_id = e.organization_id AND ep.environment_id = e.id
            LEFT JOIN LATERAL (SELECT r.status, r.created_at FROM test_run r WHERE r.organization_id = e.organization_id AND r.environment_id = e.id ORDER BY r.created_at DESC LIMIT 1) lr ON true
            """;

    private static EnvironmentView env(java.sql.ResultSet rs) throws java.sql.SQLException {
        String url = rs.getString(6);
        return new EnvironmentView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), url, rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getBoolean(18), rs.getBoolean(19), ExecutionLock.isLocal(url), array(rs, 10), array(rs, 11), rs.getString(12), rs.getInt(13), rs.getString(14),
                rs.getString(15), rs.getString(16), rs.getObject(17, OffsetDateTime.class));
    }

    @GetMapping("/environments")
    public List<EnvironmentView> environments(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.VIEWER);
        return db.sql(ENV + " WHERE e.organization_id = ? AND t.project_id = ? AND e.archived_at IS NULL ORDER BY t.name, e.name")
                .params(p.organizationId(), projectId).query((rs, n) -> env(rs)).list();
    }

    private EnvironmentView one(Principal p, UUID projectId, UUID id) {
        return db.sql(ENV + " WHERE e.organization_id = ? AND t.project_id = ? AND e.id = ?").params(p.organizationId(), projectId, id)
                .query((rs, n) -> env(rs)).optional().orElseThrow(() -> new NotFoundException("Environment"));
    }

    @PostMapping("/targets/{targetId}/environments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public EnvironmentView createEnvironment(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID targetId,
                                             @Valid @RequestBody CreateEnvironment body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        requireTarget(p, projectId, targetId);
        String type = db.sql("SELECT type FROM target WHERE organization_id = ? AND id = ?").params(p.organizationId(), targetId).query(String.class).single();
        return one(p, projectId, insertEnvironment(p, projectId, targetId, type, body));
    }

    private UUID insertEnvironment(Principal p, UUID projectId, UUID targetId, String type, CreateEnvironment body) {
        if (!CLASSES.contains(body.classification())) throw new ConflictException("Unknown environment class");
        String baseUrl = normalized(body.baseUrl());
        List<String> include = body.includePaths() == null ? List.of() : body.includePaths();
        if (body.classification().equals("PRODUCTION") && include.isEmpty()) throw new ConflictException("Production needs explicit include paths");
        String channel = body.channel() == null ? type : body.channel();
        if (!Set.of("WEB", "API").contains(channel)) throw new ConflictException("Unknown channel");
        if (body.apiSpecPath() != null && !body.apiSpecPath().matches(RELATIVE)) throw new ConflictException("Spec path must be a relative path");
        List<String> seeds = body.apiSeedPaths() == null ? List.of() : body.apiSeedPaths();
        if (seeds.stream().anyMatch(s -> !s.matches(RELATIVE))) throw new ConflictException("Seed paths must be relative");

        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO environment (organization_id, id, target_id, name, classification, base_url, authorized_by, authorized_at,
                                         channel, api_spec_path, api_slow_ms, api_seed_paths, description, active)
                VALUES (?,?,?,?,?,?,?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END, ?,?,?,?,?,?)
                """).params(p.organizationId(), id, targetId, body.name(), body.classification(), baseUrl, body.authorizedBy(), body.authorizedBy(),
                channel, body.apiSpecPath(), body.apiSlowMillis() == null ? 1000 : body.apiSlowMillis(), seeds.toArray(String[]::new), body.description() == null ? "" : body.description(),
                body.active() == null || body.active()).update();
        String origin = BaseUrl.origin(baseUrl);
        db.sql("INSERT INTO allowed_host (organization_id, environment_id, origin) VALUES (?,?,?)").params(p.organizationId(), id, origin).update();
        db.sql("INSERT INTO execution_policy (organization_id, environment_id, include_paths, exclude_paths) VALUES (?,?,?,?)")
                .params(p.organizationId(), id, include.toArray(String[]::new),
                        (body.excludePaths() == null ? List.<String>of() : body.excludePaths()).toArray(String[]::new)).update();
        audit.record(p.organizationId(), projectId, p.email(), "ENVIRONMENT_CREATED", id.toString(), body.classification() + " " + origin);
        return id;
    }

    @PostMapping("/environments/{environmentId}/{change:activate|deactivate}")
    @Transactional
    public EnvironmentView setEnvironmentActive(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID environmentId, @PathVariable String change) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        one(p, projectId, environmentId);
        boolean active = change.equals("activate");
        int updated = db.sql("UPDATE environment SET active = ? WHERE organization_id = ? AND id = ? AND archived_at IS NULL").params(active, p.organizationId(), environmentId).update();
        if (updated == 0) throw new ConflictException("This environment is archived");
        audit.record(p.organizationId(), projectId, p.email(), active ? "ENVIRONMENT_ACTIVATED" : "ENVIRONMENT_DEACTIVATED", environmentId.toString(), "");
        return one(p, projectId, environmentId);
    }

    /** The base URL and class cannot change: they are what any authorization was given for. */
    @PatchMapping("/environments/{environmentId}")
    @Transactional
    public EnvironmentView updateEnvironment(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID environmentId, @Valid @RequestBody UpdateEnvironment body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        EnvironmentView current = one(p, projectId, environmentId);
        List<String> include = body.includePaths() == null ? List.of() : body.includePaths();
        if (current.classification().equals("PRODUCTION") && include.isEmpty()) throw new ConflictException("Production needs explicit include paths");
        if (body.apiSpecPath() != null && !body.apiSpecPath().isEmpty() && !body.apiSpecPath().matches(RELATIVE)) throw new ConflictException("Spec path must be a relative path");
        List<String> exclude = body.excludePaths() == null ? List.of() : body.excludePaths();
        if (include.stream().anyMatch(s -> !s.matches(RELATIVE)) || exclude.stream().anyMatch(s -> !s.matches(RELATIVE))) throw new ConflictException("Paths must be relative");
        db.sql("UPDATE environment SET name = ?, description = ?, api_spec_path = ?, api_slow_ms = ? WHERE organization_id = ? AND id = ?")
                .params(body.name(), body.description() == null ? "" : body.description(), body.apiSpecPath() == null || body.apiSpecPath().isEmpty() ? null : body.apiSpecPath(),
                        body.apiSlowMillis() == null ? current.apiSlowMillis() : body.apiSlowMillis(), p.organizationId(), environmentId).update();
        db.sql("UPDATE execution_policy SET include_paths = ?, exclude_paths = ? WHERE organization_id = ? AND environment_id = ?")
                .params(include.toArray(String[]::new), exclude.toArray(String[]::new), p.organizationId(), environmentId).update();
        audit.record(p.organizationId(), projectId, p.email(), "ENVIRONMENT_UPDATED", environmentId.toString(), body.name());
        return one(p, projectId, environmentId);
    }

    @PostMapping("/environments/{environmentId}/archive")
    @Transactional
    public void archiveEnvironment(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        one(p, projectId, environmentId);
        db.sql("UPDATE environment SET archived_at = now() WHERE organization_id = ? AND id = ?").params(p.organizationId(), environmentId).update();
        audit.record(p.organizationId(), projectId, p.email(), "ENVIRONMENT_ARCHIVED", environmentId.toString(), "");
    }
}
