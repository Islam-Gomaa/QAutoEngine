package qaengine.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

/** Organization members. Self-service tenants and invitations are out of scope. */
@RestController
@RequestMapping("/api/v1")
public class UserController {

    public record CreateUser(@Email String email, @Size(min = 12) String password, boolean organizationAdmin) {}
    public record UserView(UUID id, String email, String role, boolean disabled) {}

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

    /** Takes effect at the next login: the account can no longer sign in. */
    @PostMapping("/users/{id}/disable")
    @Transactional
    public void disable(@AuthenticationPrincipal Principal p, @PathVariable UUID id) {
        access.requireOrganizationAdmin(p);
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
