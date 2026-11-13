package qaengine.platform.credentials;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

/**
 * Write-only: names, masks and where a credential is used can be listed; values cannot be read.
 * The login endpoints are the original per-environment form, kept working on top of the
 * environment's default sign-in profile.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class CredentialController {

    /** @param environmentId the environment this credential belongs to; when omitted it is bound to the first environment that uses it */
    public record CreateCredential(@NotBlank String name, UUID environmentId, String username, String password, String totpSeed, String cookieName, String cookieValue, String lowPrivilegeToken) {}
    /**
     * Names, masks and where the credential is used. There is no field here, or anywhere, that carries a value.
     *
     * @param usernameHint first and last character of the username only
     * @param storedFields which kinds of value are sealed, such as "password", never the values
     * @param shared true only for a credential that several environments used before binding existed
     */
    public record CredentialView(UUID id, String name, UUID environmentId, String environmentName, boolean shared, boolean enabled, String usernameHint,
                                 List<String> storedFields, OffsetDateTime createdAt, OffsetDateTime updatedAt, List<String> usedBy) {}
    /** Every field is optional. A blank or missing value keeps what is stored; nothing is erased by omission. */
    public record UpdateCredential(String name, String username, String password, String totpSeed, String cookieName, String cookieValue, String lowPrivilegeToken) {}
    /** @param lastTest the environment's latest connection test, with or without a profile; null when never tested */
    public record LoginProfile(UUID environmentId, String environmentName, String applicationName, String channel, String method, UUID credentialId, String credentialName,
                               String loginPath, String submitPath, String successPath, AuthProfiles.LastTest lastTest) {}
    public record LoginConfig(String method, UUID credentialId, String loginPath, String submitPath, String successPath) {}

    private final JdbcClient db;
    private final Access access;
    private final CredentialService credentials;
    private final AuthProfiles profiles;
    private final AuditLog audit;

    public CredentialController(JdbcClient db, Access access, CredentialService credentials, AuthProfiles profiles, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.credentials = credentials;
        this.profiles = profiles;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT c.id, c.name, c.environment_id, e.name, c.shared, c.enabled, c.username_hint, c.secret_fields, c.created_at, c.updated_at,
                   ARRAY(SELECT ue.name || ' / ' || ap.name FROM auth_profile ap JOIN environment ue ON ue.organization_id = ap.organization_id AND ue.id = ap.environment_id
                         WHERE ap.organization_id = c.organization_id AND ap.credential_id = c.id AND ue.archived_at IS NULL ORDER BY ue.name, ap.name)
            FROM credential c LEFT JOIN environment e ON e.organization_id = c.organization_id AND e.id = c.environment_id
            """;

    private static CredentialView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CredentialView(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6),
                rs.getString(7), Arrays.asList((String[]) rs.getArray(8).getArray()), rs.getObject(9, OffsetDateTime.class), rs.getObject(10, OffsetDateTime.class),
                Arrays.asList((String[]) rs.getArray(11).getArray()));
    }

    private CredentialView one(Principal p, UUID projectId, UUID id) {
        return db.sql(SELECT + " WHERE c.organization_id = ? AND c.project_id = ? AND c.id = ?").params(p.organizationId(), projectId, id)
                .query((rs, n) -> view(rs)).optional().orElseThrow(() -> new NotFoundException("Credential"));
    }

    @GetMapping("/credentials")
    public List<CredentialView> list(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        return db.sql(SELECT + " WHERE c.organization_id = ? AND c.project_id = ? ORDER BY c.name").params(p.organizationId(), projectId).query((rs, n) -> view(rs)).list();
    }

    @PostMapping("/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CredentialView create(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @Valid @RequestBody CreateCredential body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        if (body.environmentId() != null) {
            boolean ok = db.sql("""
                    SELECT count(*) FROM environment e JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                    WHERE e.organization_id = ? AND e.id = ? AND t.project_id = ? AND e.archived_at IS NULL
                    """).params(p.organizationId(), body.environmentId(), projectId).query(Long.class).single() == 1;
            if (!ok) throw new NotFoundException("Environment");
        }
        UUID id = credentials.store(p.organizationId(), projectId, body.environmentId(), body.name().strip(),
                new CredentialService.Material(body.username(), body.password(), body.totpSeed(), body.cookieName(), body.cookieValue(), body.lowPrivilegeToken()));
        audit.record(p.organizationId(), projectId, p.email(), "CREDENTIAL_CREATED", id.toString(), body.name());
        return one(p, projectId, id);
    }

    /** Replaces only the values given; the response says nothing about them beyond their names. */
    @PutMapping("/credentials/{id}")
    @Transactional
    public CredentialView update(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID id, @RequestBody UpdateCredential body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        one(p, projectId, id);
        if (body.name() != null && !body.name().isBlank()) {
            db.sql("UPDATE credential SET name = ?, updated_at = now() WHERE organization_id = ? AND id = ?").params(body.name().strip(), p.organizationId(), id).update();
        }
        List<String> changed = credentials.update(p.organizationId(), id,
                new CredentialService.Material(body.username(), body.password(), body.totpSeed(), body.cookieName(), body.cookieValue(), body.lowPrivilegeToken()));
        audit.record(p.organizationId(), projectId, p.email(), "CREDENTIAL_UPDATED", id.toString(), changed.isEmpty() ? "no values replaced" : "replaced " + String.join(", ", changed));
        return one(p, projectId, id);
    }

    @PostMapping("/credentials/{id}/{change:enable|disable}")
    @Transactional
    public CredentialView setEnabled(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID id, @PathVariable String change) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        one(p, projectId, id);
        boolean enabled = change.equals("enable");
        db.sql("UPDATE credential SET enabled = ?, updated_at = now() WHERE organization_id = ? AND id = ?").params(enabled, p.organizationId(), id).update();
        audit.record(p.organizationId(), projectId, p.email(), enabled ? "CREDENTIAL_ENABLED" : "CREDENTIAL_DISABLED", id.toString(), "");
        return one(p, projectId, id);
    }

    @DeleteMapping("/credentials/{id}")
    @Transactional
    public void delete(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID id) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        one(p, projectId, id);
        long inUse = db.sql("SELECT count(*) FROM auth_profile WHERE organization_id = ? AND credential_id = ?").params(p.organizationId(), id).query(Long.class).single();
        if (inUse > 0) throw new ConflictException("This credential is used by a login profile");
        db.sql("DELETE FROM credential WHERE organization_id = ? AND id = ?").params(p.organizationId(), id).update();
        audit.record(p.organizationId(), projectId, p.email(), "CREDENTIAL_DELETED", id.toString(), "");
    }

    // ---- the original per-environment login form, on top of the default sign-in profile

    @GetMapping("/login-profiles")
    public List<LoginProfile> loginProfiles(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        return db.sql("""
                SELECT e.id, e.name, t.name, e.channel, ap.method, ap.credential_id, c.name, ap.login_path, ap.submit_path, ap.success_path,
                       lt.run_id, lt.run_status, lt.status, lt.failure, lt.stage, lt.detail, lt.at, lt.suggestions::text
                FROM environment e JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                LEFT JOIN auth_profile ap ON ap.organization_id = e.organization_id AND ap.environment_id = e.id AND ap.is_default
                LEFT JOIN credential c ON c.organization_id = ap.organization_id AND c.id = ap.credential_id
                LEFT JOIN LATERAL (
                    SELECT r.id AS run_id, r.status AS run_status, aa.status, aa.failure, aa.stage, aa.detail, coalesce(aa.created_at, r.created_at) AS at, aa.suggestions
                    FROM test_run r LEFT JOIN auth_attempt aa ON aa.organization_id = r.organization_id AND aa.run_id = r.id
                    WHERE r.organization_id = e.organization_id AND r.environment_id = e.id AND r.kind = 'AUTH_TEST'
                    ORDER BY r.created_at DESC, aa.attempt DESC NULLS LAST, aa.sign_in DESC NULLS LAST LIMIT 1) lt ON true
                WHERE e.organization_id = ? AND t.project_id = ? AND e.archived_at IS NULL ORDER BY t.name, e.name
                """).params(p.organizationId(), projectId).query((rs, n) -> new LoginProfile(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getObject(11) == null ? null : new AuthProfiles.LastTest(rs.getObject(11, UUID.class), rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15),
                        rs.getString(16), rs.getObject(17, java.time.OffsetDateTime.class), profiles.suggestions(rs.getString(18))))).list();
    }

    @DeleteMapping("/environments/{environmentId}/login")
    @Transactional
    public void clearLogin(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        requireEnvironment(p, projectId, environmentId);
        profiles.defaultFor(p.organizationId(), environmentId).ifPresent(v -> profiles.delete(p, projectId, v.id()));
        audit.record(p.organizationId(), projectId, p.email(), "LOGIN_PROFILE_CLEARED", environmentId.toString(), "");
    }

    @PutMapping("/environments/{environmentId}/login")
    @Transactional
    public void configure(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID environmentId, @Valid @RequestBody LoginConfig body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        String channel = requireEnvironment(p, projectId, environmentId);
        if (!AuthProfiles.METHODS.contains(String.valueOf(body.method()))) throw new ConflictException("Unsupported login method");
        // This form has never had a way to finish a profile later, so a web login still needs its success path here.
        if (channel.equals("WEB") && (body.successPath() == null || body.successPath().isBlank())) throw new ConflictException("A web login needs a success path");
        var current = profiles.defaultFor(p.organizationId(), environmentId);
        var settings = new AuthProfiles.Settings(current.map(AuthProfiles.View::name).orElse("Default"), body.method(), body.credentialId(),
                body.loginPath(), body.submitPath(), body.successPath(), null, true, null, null, null, null);
        if (current.isPresent()) profiles.update(p, projectId, current.get().id(), settings);
        else profiles.create(p, projectId, environmentId, settings);
        audit.record(p.organizationId(), projectId, p.email(), "LOGIN_PROFILE_SET", environmentId.toString(), body.method());
    }

    private String requireEnvironment(Principal p, UUID projectId, UUID environmentId) {
        return db.sql("""
                SELECT e.channel FROM environment e JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                WHERE e.organization_id = ? AND e.id = ? AND t.project_id = ?
                """).params(p.organizationId(), environmentId, projectId).query(String.class).optional().orElseThrow(() -> new NotFoundException("Environment"));
    }
}
