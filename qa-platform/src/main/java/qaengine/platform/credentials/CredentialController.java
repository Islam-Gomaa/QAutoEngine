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

    public record CreateCredential(@NotBlank String name, String username, String password, String totpSeed, String cookieName, String cookieValue) {}
    public record CredentialView(UUID id, String name) {}
    public record LoginConfig(String method, UUID credentialId, String loginPath, String submitPath, @NotBlank String successPath) {}

    private static final Set<String> METHODS = Set.of("PASSWORD", "TOTP", "SESSION_IMPORT");

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
        return db.sql("SELECT id, name FROM credential WHERE organization_id = ? AND project_id = ? ORDER BY name")
                .params(p.organizationId(), projectId).query((rs, n) -> new CredentialView(rs.getObject(1, UUID.class), rs.getString(2))).list();
    }

    @PostMapping("/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CredentialView create(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @Valid @RequestBody CreateCredential body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        UUID id = credentials.store(p.organizationId(), projectId, body.name(),
                new CredentialService.Material(body.username(), body.password(), body.totpSeed(), body.cookieName(), body.cookieValue()));
        audit.record(p.organizationId(), p.email(), "CREDENTIAL_CREATED", id.toString(), body.name());
        return new CredentialView(id, body.name());
    }

    @PutMapping("/environments/{environmentId}/login")
    @Transactional
    public void configure(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID environmentId, @Valid @RequestBody LoginConfig body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        if (!METHODS.contains(body.method())) throw new ConflictException("Unsupported login method");
        String classification = db.sql("""
                SELECT e.classification FROM environment e JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                WHERE e.organization_id = ? AND e.id = ? AND t.project_id = ?
                """).params(p.organizationId(), environmentId, projectId).query(String.class).optional().orElseThrow(() -> new NotFoundException("Environment"));
        if (classification.equals("PRODUCTION") && !p.organizationAdmin()) throw new AccessDeniedException("Production login needs Organization Admin");
        boolean sameProject = db.sql("SELECT count(*) FROM credential WHERE organization_id = ? AND project_id = ? AND id = ?")
                .params(p.organizationId(), projectId, body.credentialId()).query(Long.class).single() == 1;
        if (!sameProject) throw new NotFoundException("Credential");
        db.sql("""
                UPDATE environment SET login_method = ?, login_credential_id = ?, login_path = ?, login_submit_path = ?, login_success_path = ?
                WHERE organization_id = ? AND id = ?
                """).params(body.method(), body.credentialId(), body.loginPath(), body.submitPath(), body.successPath(), p.organizationId(), environmentId).update();
        audit.record(p.organizationId(), p.email(), "LOGIN_PROFILE_SET", environmentId.toString(), body.method());
    }
}
