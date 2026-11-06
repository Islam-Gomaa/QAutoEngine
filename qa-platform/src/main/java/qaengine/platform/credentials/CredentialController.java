package qaengine.platform.credentials;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
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

/** Write-only: names can be listed, values cannot be read. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class CredentialController {

    public record CreateCredential(@NotBlank String name, String username, String password, String totpSeed, String cookieName, String cookieValue, String lowPrivilegeToken) {}
    /** Names and where they are used. There is no field here, or anywhere, that carries a value. */
    public record CredentialView(UUID id, String name, java.time.OffsetDateTime createdAt, List<String> usedBy) {}
    public record Rotate(String username, String password, String totpSeed, String cookieName, String cookieValue, String lowPrivilegeToken) {}
    public record LoginProfile(UUID environmentId, String environmentName, String applicationName, String channel, String method, UUID credentialId, String credentialName,
                               String loginPath, String submitPath, String successPath) {}
    public record LoginConfig(String method, UUID credentialId, String loginPath, String submitPath, String successPath) {}

    private static final Set<String> METHODS = Set.of("PASSWORD", "TOTP", "SESSION_IMPORT", "API_TOKEN");

    private final JdbcClient db;
    private final Access access;
    private final CredentialService credentials;
    private final AuditLog audit;

    public CredentialController(JdbcClient db, Access access, CredentialService credentials, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.credentials = credentials;
        this.audit = audit;
    }

    @GetMapping("/credentials")
    public List<CredentialView> list(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        return db.sql("""
                SELECT c.id, c.name, c.created_at,
                       ARRAY(SELECT e.name FROM environment e WHERE e.organization_id = c.organization_id AND e.login_credential_id = c.id AND e.archived_at IS NULL ORDER BY e.name)
                FROM credential c WHERE c.organization_id = ? AND c.project_id = ? ORDER BY c.name
                """).params(p.organizationId(), projectId).query((rs, n) -> new CredentialView(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, java.time.OffsetDateTime.class),
                java.util.Arrays.asList((String[]) rs.getArray(4).getArray()))).list();
    }

    @PostMapping("/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CredentialView create(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @Valid @RequestBody CreateCredential body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        UUID id = credentials.store(p.organizationId(), projectId, body.name(),
                new CredentialService.Material(body.username(), body.password(), body.totpSeed(), body.cookieName(), body.cookieValue(), body.lowPrivilegeToken()));
        audit.record(p.organizationId(), projectId, p.email(), "CREDENTIAL_CREATED", id.toString(), body.name());
        return new CredentialView(id, body.name(), java.time.OffsetDateTime.now(), List.of());
    }

    private void requireCredential(Principal p, UUID projectId, UUID id) {
        boolean ok = db.sql("SELECT count(*) FROM credential WHERE organization_id = ? AND project_id = ? AND id = ?").params(p.organizationId(), projectId, id).query(Long.class).single() == 1;
        if (!ok) throw new NotFoundException("Credential");
    }

    /** Replaces the sealed material. The old value cannot be read back, so all fields are given again. */
    @PutMapping("/credentials/{id}")
    @Transactional
    public void rotate(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID id, @RequestBody Rotate body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        requireCredential(p, projectId, id);
        credentials.replace(p.organizationId(), id, new CredentialService.Material(body.username(), body.password(), body.totpSeed(), body.cookieName(), body.cookieValue(), body.lowPrivilegeToken()));
        audit.record(p.organizationId(), projectId, p.email(), "CREDENTIAL_ROTATED", id.toString(), "");
    }

    @DeleteMapping("/credentials/{id}")
    @Transactional
    public void delete(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID id) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        requireCredential(p, projectId, id);
        long inUse = db.sql("SELECT count(*) FROM environment WHERE organization_id = ? AND login_credential_id = ?").params(p.organizationId(), id).query(Long.class).single();
        if (inUse > 0) throw new ConflictException("This credential is used by a login profile");
        db.sql("DELETE FROM credential WHERE organization_id = ? AND id = ?").params(p.organizationId(), id).update();
        audit.record(p.organizationId(), projectId, p.email(), "CREDENTIAL_DELETED", id.toString(), "");
    }

    @GetMapping("/login-profiles")
    public List<LoginProfile> loginProfiles(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        return db.sql("""
                SELECT e.id, e.name, t.name, e.channel, e.login_method, e.login_credential_id, c.name, e.login_path, e.login_submit_path, e.login_success_path
                FROM environment e JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                LEFT JOIN credential c ON c.organization_id = e.organization_id AND c.id = e.login_credential_id
                WHERE e.organization_id = ? AND t.project_id = ? AND e.archived_at IS NULL ORDER BY t.name, e.name
                """).params(p.organizationId(), projectId).query((rs, n) -> new LoginProfile(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10))).list();
    }

    @DeleteMapping("/environments/{environmentId}/login")
    @Transactional
    public void clearLogin(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        int updated = db.sql("""
                UPDATE environment e SET login_method = NULL, login_credential_id = NULL, login_path = NULL, login_submit_path = NULL, login_success_path = NULL
                FROM target t WHERE t.organization_id = e.organization_id AND t.id = e.target_id AND e.organization_id = ? AND e.id = ? AND t.project_id = ?
                """).params(p.organizationId(), environmentId, projectId).update();
        if (updated == 0) throw new NotFoundException("Environment");
        audit.record(p.organizationId(), projectId, p.email(), "LOGIN_PROFILE_CLEARED", environmentId.toString(), "");
    }

    @PutMapping("/environments/{environmentId}/login")
    @Transactional
    public void configure(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID environmentId, @Valid @RequestBody LoginConfig body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        if (!METHODS.contains(body.method())) throw new ConflictException("Unsupported login method");
        String[] environment = db.sql("""
                SELECT e.classification, e.channel FROM environment e JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                WHERE e.organization_id = ? AND e.id = ? AND t.project_id = ?
                """).params(p.organizationId(), environmentId, projectId).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).optional().orElseThrow(() -> new NotFoundException("Environment"));
        if (environment[0].equals("PRODUCTION") && !p.organizationAdmin()) throw new AccessDeniedException("Production login needs Organization Admin");
        // A password must never be sent as a bearer token, and a token never typed into a login form.
        if (environment[1].equals("API") != body.method().equals("API_TOKEN")) throw new ConflictException("Login method does not fit this channel");
        if (environment[1].equals("WEB") && (body.successPath() == null || body.successPath().isBlank())) throw new ConflictException("A web login needs a success path");
        boolean sameProject = db.sql("SELECT count(*) FROM credential WHERE organization_id = ? AND project_id = ? AND id = ?")
                .params(p.organizationId(), projectId, body.credentialId()).query(Long.class).single() == 1;
        if (!sameProject) throw new NotFoundException("Credential");
        db.sql("""
                UPDATE environment SET login_method = ?, login_credential_id = ?, login_path = ?, login_submit_path = ?, login_success_path = ?
                WHERE organization_id = ? AND id = ?
                """).params(body.method(), body.credentialId(), body.loginPath(), body.submitPath(), body.successPath(), p.organizationId(), environmentId).update();
        audit.record(p.organizationId(), projectId, p.email(), "LOGIN_PROFILE_SET", environmentId.toString(), body.method());
    }
}
