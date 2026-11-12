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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qaengine.core.policy.BaseUrl;
import qaengine.core.policy.PolicyException;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.credentials.AuthProfiles;
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
                                    List<String> includePaths, List<String> excludePaths,
                                    String channel, String apiSpecPath, Integer apiSlowMillis, List<String> apiSeedPaths, String description, Boolean active,
                                    AuthProfiles.NewProfile authentication) {}
    public record UpdateEnvironment(@NotBlank String name, String description, List<String> includePaths, List<String> excludePaths,
                                    String apiSpecPath, Integer apiSlowMillis) {}
    /**
     * @param executable whether its target may be run, from {@link Eligibility}. False for anything but the fixture until the security gate passes
     * @param lockReason why not, when it may not: {@link Eligibility.Reason}. Null when executable. Activity is reported separately
     * @param applicationActive an inactive application stops its environments from running, whatever their own switch says
     * @param authorized a written authorization is recorded and in date. On its own it never makes an environment executable
     */
    public record EnvironmentView(UUID id, UUID targetId, String targetName, String name, String classification, String baseUrl, String mode, String channel,
                                  String description, boolean active, boolean applicationActive, boolean executable, Eligibility.Reason lockReason, boolean authorized,
                                  List<String> includePaths, List<String> excludePaths,
                                  String apiSpecPath, int apiSlowMillis, String loginMethod, String credentialName, String lastRunStatus, OffsetDateTime lastRunAt) {}
    public record BaseUrlCheck(String baseUrl) {}
    /** What would be stored. Nothing is contacted: the platform never reaches a target itself. */
    public record BaseUrlResult(String baseUrl, String origin, boolean executable) {}
    /**
     * @param reference an identifier for the written authorization, such as a document number. Never the document itself
     * @param expiresAt the end of the window the authorization covers
     */
    public record RecordAuthorization(String reference, OffsetDateTime expiresAt) {}
    /**
     * @param authorized recorded and in date
     * @param expired recorded, but its window has ended
     * @param authorizedBy the Organization Admin who recorded it
     */
    public record AuthorizationView(boolean authorized, boolean expired, String reference, String authorizedBy, OffsetDateTime authorizedAt, OffsetDateTime expiresAt) {}

    private static final Set<String> CLASSES = Set.of("LOCAL", "DEV", "QA", "UAT", "STAGING", "PRODUCTION");
    private static final Set<String> TYPES = Set.of("WEB", "API");
    private static final String RELATIVE = "/[A-Za-z0-9._~/-]*";

    private final JdbcClient db;
    private final Access access;
    private final AuditLog audit;
    private final AuthProfiles profiles;

    public TargetController(JdbcClient db, Access access, AuditLog audit, AuthProfiles profiles) {
        this.db = db;
        this.access = access;
        this.audit = audit;
        this.profiles = profiles;
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
        // A URL not yet saved has no authorization, so only the fixture can be executable here.
        return new BaseUrlResult(url, BaseUrl.origin(url), Eligibility.of(url, null, null, java.time.Instant.now()).executable());
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
                   e.api_spec_path, e.api_slow_ms, ap.method, ap.credential_name,
                   lr.status, lr.created_at, e.active, t.active, e.authorization_reference, e.authorization_expires_at
            FROM environment e
            JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
            JOIN execution_policy ep ON ep.organization_id = e.organization_id AND ep.environment_id = e.id
            LEFT JOIN LATERAL (SELECT a.method, c.name AS credential_name FROM auth_profile a JOIN credential c ON c.organization_id = a.organization_id AND c.id = a.credential_id
                               WHERE a.organization_id = e.organization_id AND a.environment_id = e.id AND a.is_default) ap ON true
            LEFT JOIN LATERAL (SELECT r.status, r.created_at FROM test_run r WHERE r.organization_id = e.organization_id AND r.environment_id = e.id ORDER BY r.created_at DESC LIMIT 1) lr ON true
            """;

    private static EnvironmentView env(java.sql.ResultSet rs) throws java.sql.SQLException {
        String url = rs.getString(6);
        String reference = rs.getString(20);
        OffsetDateTime expiresAt = rs.getObject(21, OffsetDateTime.class);
        java.time.Instant now = java.time.Instant.now();
        Eligibility.Result eligibility = Eligibility.of(url, reference, expiresAt, now);
        boolean authorized = reference != null && expiresAt != null && expiresAt.toInstant().isAfter(now);
        return new EnvironmentView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), url, rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getBoolean(18), rs.getBoolean(19), eligibility.executable(), eligibility.lockReason(), authorized, array(rs, 10), array(rs, 11), rs.getString(12), rs.getInt(13), rs.getString(14),
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
                INSERT INTO environment (organization_id, id, target_id, name, classification, base_url,
                                         channel, api_spec_path, api_slow_ms, api_seed_paths, description, active)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """).params(p.organizationId(), id, targetId, body.name(), body.classification(), baseUrl,
                channel, body.apiSpecPath(), body.apiSlowMillis() == null ? 1000 : body.apiSlowMillis(), seeds.toArray(String[]::new), body.description() == null ? "" : body.description(),
                body.active() == null || body.active()).update();
        String origin = BaseUrl.origin(baseUrl);
        db.sql("INSERT INTO allowed_host (organization_id, environment_id, origin) VALUES (?,?,?)").params(p.organizationId(), id, origin).update();
        db.sql("INSERT INTO execution_policy (organization_id, environment_id, include_paths, exclude_paths) VALUES (?,?,?,?)")
                .params(p.organizationId(), id, include.toArray(String[]::new),
                        (body.excludePaths() == null ? List.<String>of() : body.excludePaths()).toArray(String[]::new)).update();
        audit.record(p.organizationId(), projectId, p.email(), "ENVIRONMENT_CREATED", id.toString(), body.classification() + " " + origin);
        if (body.authentication() != null) {
            // A new credential, bound to this environment only, and a default profile that uses it.
            String application = db.sql("SELECT name FROM target WHERE organization_id = ? AND id = ?").params(p.organizationId(), targetId).query(String.class).single();
            String profile = body.authentication().name() == null || body.authentication().name().isBlank() ? "Default" : body.authentication().name().strip();
            profiles.createWithCredential(p, projectId, id, application + " / " + body.name() + " / " + profile, body.authentication());
        }
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

    // ---- written authorization to test a real environment
    // A record only. Nothing here makes an environment executable: that stays ExecutionLock's decision alone.

    /** An identifier such as a document number: no line breaks, no markup, nothing long enough to be the document itself. */
    private static final java.util.regex.Pattern REFERENCE = java.util.regex.Pattern.compile("[A-Za-z0-9 ._:/#()-]{3,120}");

    /** A written authorization covers a window, and no window is open-ended. */
    private static final java.time.Duration LONGEST_AUTHORIZATION = java.time.Duration.ofDays(366);

    private record Authorization(String baseUrl, boolean archived, String reference, String authorizedBy, OffsetDateTime authorizedAt, OffsetDateTime expiresAt) {
        AuthorizationView view() {
            boolean recorded = reference != null;
            boolean expired = recorded && !expiresAt.toInstant().isAfter(java.time.Instant.now());
            return new AuthorizationView(recorded && !expired, expired, reference, authorizedBy, authorizedAt, expiresAt);
        }

        /** How the audit names it: the reference and, in UTC to the second, when it ends. */
        String described() {
            return describe(reference, expiresAt);
        }
    }

    private static String describe(String reference, OffsetDateTime expiresAt) {
        return "ref=" + reference + " until=" + expiresAt.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    /** Locks the row when changing it, so two admins cannot record over each other and leave the audit out of step. */
    private Authorization authorization(Principal p, UUID projectId, UUID environmentId, boolean forUpdate) {
        return db.sql("""
                SELECT e.base_url, e.archived_at IS NOT NULL, e.authorization_reference, e.authorized_by, e.authorized_at, e.authorization_expires_at FROM environment e
                JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                WHERE e.organization_id = ? AND t.project_id = ? AND e.id = ?""" + (forUpdate ? " FOR UPDATE OF e" : ""))
                .params(p.organizationId(), projectId, environmentId)
                .query((rs, n) -> new Authorization(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class),
                        rs.getObject(6, OffsetDateTime.class)))
                .optional().orElseThrow(() -> new NotFoundException("Environment"));
    }

    @GetMapping("/environments/{environmentId}/authorization")
    public AuthorizationView authorization(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        return authorization(p, projectId, environmentId, false).view();
    }

    /** Records, or replaces, the written authorization. Organization Admin only, so whoever adds a target cannot also authorize it. */
    @PutMapping("/environments/{environmentId}/authorization")
    @Transactional
    public AuthorizationView recordAuthorization(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID environmentId,
                                                 @RequestBody RecordAuthorization body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        access.requireOrganizationAdmin(p);
        Authorization current = authorization(p, projectId, environmentId, true);
        if (current.archived()) throw new ConflictException("This environment is archived");
        // The fixture by its own list, not by ExecutionLock: a real host approved there one day must still be authorizable.
        if (Eligibility.isFixture(current.baseUrl())) throw new ConflictException("The local fixture needs no authorization");
        String reference = body == null || body.reference() == null ? "" : body.reference().strip();
        if (!REFERENCE.matcher(reference).matches()) throw new ConflictException("Authorization reference is missing or invalid");
        if (body.expiresAt() == null) throw new ConflictException("Authorization expiry is missing");
        // Stored to the second, so sending the same window again is recognized as no change.
        OffsetDateTime expiresAt = body.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        java.time.Instant now = java.time.Instant.now();
        if (!expiresAt.toInstant().isAfter(now)) throw new ConflictException("Authorization expiry must be in the future");
        if (expiresAt.toInstant().isAfter(now.plus(LONGEST_AUTHORIZATION))) throw new ConflictException("An authorization cannot last longer than a year");
        if (reference.equals(current.reference()) && expiresAt.toInstant().equals(current.expiresAt().toInstant())) return current.view();
        db.sql("UPDATE environment SET authorization_reference = ?, authorization_expires_at = ?, authorized_by = ?, authorized_at = now() WHERE organization_id = ? AND id = ?")
                .params(reference, expiresAt, p.email(), p.organizationId(), environmentId).update();
        String origin = BaseUrl.origin(current.baseUrl());
        if (current.reference() == null) {
            audit.record(p.organizationId(), projectId, p.email(), "ENVIRONMENT_AUTHORIZATION_RECORDED", environmentId.toString(), origin + " " + describe(reference, expiresAt));
        } else {
            audit.record(p.organizationId(), projectId, p.email(), "ENVIRONMENT_AUTHORIZATION_UPDATED", environmentId.toString(),
                    origin + " " + current.described() + " -> " + describe(reference, expiresAt));
        }
        return authorization(p, projectId, environmentId, false).view();
    }

    @DeleteMapping("/environments/{environmentId}/authorization")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revokeAuthorization(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        access.requireOrganizationAdmin(p);
        Authorization current = authorization(p, projectId, environmentId, true);
        if (current.reference() == null) throw new ConflictException("This environment has no authorization");
        db.sql("UPDATE environment SET authorization_reference = NULL, authorization_expires_at = NULL, authorized_by = NULL, authorized_at = NULL WHERE organization_id = ? AND id = ?")
                .params(p.organizationId(), environmentId).update();
        audit.record(p.organizationId(), projectId, p.email(), "ENVIRONMENT_AUTHORIZATION_REVOKED", environmentId.toString(),
                BaseUrl.origin(current.baseUrl()) + " " + current.described());
    }
}
