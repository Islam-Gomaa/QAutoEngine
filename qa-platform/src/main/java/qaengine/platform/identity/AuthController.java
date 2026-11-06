package qaengine.platform.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.shared.Principal;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(String email, String password, String totp) {}

    private record Account(UUID id, String hash, String totpSecret, boolean disabled, UUID organizationId, String role) {}

    /** Verified against for unknown users too, so response time does not reveal who exists. */
    private final String decoyHash;

    private final JdbcClient db;
    private final PasswordEncoder encoder;
    private final LoginThrottle throttle;
    private final AuditLog audit;
    private final SecretBox box;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public AuthController(JdbcClient db, PasswordEncoder encoder, LoginThrottle throttle, AuditLog audit, SecretBox box) {
        this.db = db;
        this.encoder = encoder;
        this.throttle = throttle;
        this.audit = audit;
        this.box = box;
        this.decoyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String email = body.email() == null ? "" : body.email().trim().toLowerCase();
        if (throttle.blocked(email)) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();

        var found = db.sql("""
                SELECT u.id, u.password_hash, u.totp_secret, u.disabled, m.organization_id, m.role
                FROM app_user u JOIN membership m ON m.user_id = u.id WHERE u.email = ?
                """).param(email).query((rs, n) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getBoolean(4), rs.getObject(5, UUID.class), rs.getString(6))).optional();

        boolean passwordOk = encoder.matches(body.password() == null ? "" : body.password(), found.map(Account::hash).orElse(decoyHash));
        if (found.isEmpty() || !passwordOk || found.get().disabled()) {
            throttle.failed(email);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Account a = found.get();
        if (a.totpSecret() != null && !Totp.verify(TotpSeal.open(box, a.id(), a.totpSecret()), body.totp(), Instant.now())) {
            throttle.failed(email);
            audit.record(a.organizationId(), email, "LOGIN_FAILED_TOTP", "", "");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        throttle.succeeded(email);
        Principal principal = new Principal(a.id(), a.organizationId(), email, a.role().equals("ORGANIZATION_ADMIN"));
        var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        request.getSession(true);
        request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        audit.record(a.organizationId(), email, "LOGIN", "", "");
        return ResponseEntity.ok(principal);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Principal p, HttpServletRequest request) {
        audit.record(p.organizationId(), p.email(), "LOGOUT", "", "");
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Principal me(@AuthenticationPrincipal Principal p) {
        return p;
    }
}
