package qaengine.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

/** Organization members. Self-service tenants and invitations are out of scope. */
@RestController
@RequestMapping("/api/v1")
public class UserController {

    public record CreateUser(@Email String email, @Size(min = 12) String password, boolean organizationAdmin) {}
    public record UserView(UUID id, String email, String role, boolean disabled) {}
    public record RoleChange(String role) {}
    public record PasswordReset(@Size(min = 12) String password) {}
    public record PasswordChange(String current, @Size(min = 12) String password) {}
    public record Organization(String name) {}
    public record Assignment(UUID projectId, String projectName, String role) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final Access access;
    private final PasswordEncoder encoder;
    private final AuditLog audit;
    private final SecretBox box;

    public UserController(JdbcClient db, Access access, PasswordEncoder encoder, AuditLog audit, SecretBox box) {
        this.db = db;
        this.access = access;
        this.encoder = encoder;
        this.audit = audit;
        this.box = box;
    }

    @GetMapping("/users")
    public List<UserView> list(@AuthenticationPrincipal Principal p) {
        access.requireOrganizationAdmin(p);
        return db.sql("""
                SELECT u.id, u.email, m.role, u.disabled FROM app_user u JOIN membership m ON m.user_id = u.id
                WHERE m.organization_id = ? ORDER BY u.email
                """).param(p.organizationId()).query((rs, n) -> new UserView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4))).list();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UserView create(@AuthenticationPrincipal Principal p, @Valid @RequestBody CreateUser body) {
        access.requireOrganizationAdmin(p);
        UUID id = UUID.randomUUID();
        String email = body.email().trim().toLowerCase();
        String role = body.organizationAdmin() ? "ORGANIZATION_ADMIN" : "MEMBER";
        db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?,?,?)").params(id, email, encoder.encode(body.password())).update();
        db.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (?,?,?)").params(p.organizationId(), id, role).update();
        audit.record(p.organizationId(), p.email(), "USER_CREATED", id.toString(), role);
        return new UserView(id, email, role, false);
    }

    /** An organization must always keep someone who can administer it. */
    private void keepAnAdmin(Principal p, UUID id) {
        if (id.equals(p.userId())) throw new ConflictException("You cannot change your own access");
        long others = db.sql("""
                SELECT count(*) FROM membership m JOIN app_user u ON u.id = m.user_id
                WHERE m.organization_id = ? AND m.role = 'ORGANIZATION_ADMIN' AND NOT u.disabled AND u.id <> ?
                """).params(p.organizationId(), id).query(Long.class).single();
        if (others == 0) throw new ConflictException("The organization needs at least one administrator");
    }

    @PatchMapping("/users/{id}/role")
    @Transactional
    public void changeRole(@AuthenticationPrincipal Principal p, @PathVariable UUID id, @RequestBody RoleChange body) {
        access.requireOrganizationAdmin(p);
        if (!Set.of("ORGANIZATION_ADMIN", "MEMBER").contains(String.valueOf(body.role()))) throw new ConflictException("Unknown role");
        keepAnAdmin(p, id);
        int updated = db.sql("UPDATE membership SET role = ? WHERE organization_id = ? AND user_id = ?").params(body.role(), p.organizationId(), id).update();
        if (updated == 0) throw new NotFoundException("User");
        audit.record(p.organizationId(), p.email(), "USER_ROLE_CHANGED", id.toString(), body.role());
    }

    @PostMapping("/users/{id}/enable")
    @Transactional
    public void enable(@AuthenticationPrincipal Principal p, @PathVariable UUID id) {
        access.requireOrganizationAdmin(p);
        int updated = db.sql("""
                UPDATE app_user u SET disabled = false FROM membership m
                WHERE m.user_id = u.id AND m.organization_id = ? AND u.id = ?
                """).params(p.organizationId(), id).update();
        if (updated == 0) throw new NotFoundException("User");
        audit.record(p.organizationId(), p.email(), "USER_ENABLED", id.toString(), "");
    }

    /** Ends the account's open sessions as well: they predate the new password. */
    @PostMapping("/users/{id}/password")
    @Transactional
    public void resetPassword(@AuthenticationPrincipal Principal p, @PathVariable UUID id, @Valid @RequestBody PasswordReset body) {
        access.requireOrganizationAdmin(p);
        int updated = db.sql("""
                UPDATE app_user u SET password_hash = ?, password_changed_at = now() FROM membership m
                WHERE m.user_id = u.id AND m.organization_id = ? AND u.id = ?
                """).params(encoder.encode(body.password()), p.organizationId(), id).update();
        if (updated == 0) throw new NotFoundException("User");
        audit.record(p.organizationId(), p.email(), "USER_PASSWORD_RESET", id.toString(), "");
    }

    @GetMapping("/users/{id}/projects")
    public List<Assignment> assignments(@AuthenticationPrincipal Principal p, @PathVariable UUID id) {
        access.requireOrganizationAdmin(p);
        return db.sql("""
                SELECT pr.id, pr.name, m.role FROM project_member m JOIN project pr ON pr.organization_id = m.organization_id AND pr.id = m.project_id
                WHERE m.organization_id = ? AND m.user_id = ? ORDER BY pr.name
                """).params(p.organizationId(), id).query((rs, n) -> new Assignment(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
    }

    @PostMapping("/auth/password")
    @Transactional
    public void changePassword(@AuthenticationPrincipal Principal p, @Valid @RequestBody PasswordChange body) {
        String hash = db.sql("SELECT password_hash FROM app_user WHERE id = ?").param(p.userId()).query(String.class).single();
        if (body.current() == null || !encoder.matches(body.current(), hash)) throw new ConflictException("The current password is not correct");
        db.sql("UPDATE app_user SET password_hash = ?, password_changed_at = now() WHERE id = ?").params(encoder.encode(body.password()), p.userId()).update();
        audit.record(p.organizationId(), p.email(), "PASSWORD_CHANGED", "", "");
    }

    @GetMapping("/organization")
    public Organization organization(@AuthenticationPrincipal Principal p) {
        return new Organization(db.sql("SELECT name FROM organization WHERE id = ?").param(p.organizationId()).query(String.class).single());
    }

    @PatchMapping("/organization")
    @Transactional
    public Organization renameOrganization(@AuthenticationPrincipal Principal p, @RequestBody Organization body) {
        access.requireOrganizationAdmin(p);
        if (body.name() == null || body.name().isBlank() || body.name().length() > 120) throw new ConflictException("Give the organization a name of up to 120 characters");
        db.sql("UPDATE organization SET name = ? WHERE id = ?").params(body.name().trim(), p.organizationId()).update();
        audit.record(p.organizationId(), p.email(), "ORGANIZATION_RENAMED", "", body.name());
        return new Organization(body.name().trim());
    }

    /** Takes effect on the account's next request. */
    @PostMapping("/users/{id}/disable")
    @Transactional
    public void disable(@AuthenticationPrincipal Principal p, @PathVariable UUID id) {
        access.requireOrganizationAdmin(p);
        keepAnAdmin(p, id);
        int updated = db.sql("""
                UPDATE app_user u SET disabled = true FROM membership m
                WHERE m.user_id = u.id AND m.organization_id = ? AND u.id = ?
                """).params(p.organizationId(), id).update();
        if (updated == 0) throw new NotFoundException("User");
        audit.record(p.organizationId(), p.email(), "USER_DISABLED", id.toString(), "");
    }

    /** The seed is shown once, for the authenticator app, and stored only sealed. */
    @PostMapping("/auth/totp")
    @Transactional
    public Map<String, String> enrollTotp(@AuthenticationPrincipal Principal p) {
        byte[] seed = new byte[20];
        RANDOM.nextBytes(seed);
        String encoded = Base64.getEncoder().encodeToString(seed);
        db.sql("UPDATE app_user SET totp_secret = ? WHERE id = ?").params(TotpSeal.seal(box, p.userId(), encoded), p.userId()).update();
        audit.record(p.organizationId(), p.email(), "TOTP_ENROLLED", "", "");
        return Map.of("seedBase64", encoded);
    }
}
