package qaengine.platform.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import qaengine.platform.shared.Principal;

/**
 * Sessions can outlive a change to the account. Each request re-reads the user, so disabling someone
 * changing their role or resetting their password takes effect at once rather than at their next login.
 */
@Component
public class FreshPrincipalFilter extends OncePerRequestFilter {

    private final JdbcClient db;

    public FreshPrincipalFilter(JdbcClient db) {
        this.db = db;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Principal p) {
            record Account(String role, long passwordChanged) {}
            var current = db.sql("""
                    SELECT m.role, coalesce(extract(epoch FROM u.password_changed_at) * 1000, 0)::bigint FROM app_user u JOIN membership m ON m.user_id = u.id
                    WHERE u.id = ? AND m.organization_id = ? AND NOT u.disabled
                    """).params(p.userId(), p.organizationId()).query((rs, n) -> new Account(rs.getString(1), rs.getLong(2))).optional();
            var session = request.getSession(false);
            if (current.isEmpty() || (session != null && session.getCreationTime() < current.get().passwordChanged())) {
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            boolean admin = current.get().role().equals("ORGANIZATION_ADMIN");
            if (admin != p.organizationAdmin()) {
                var fresh = new Principal(p.userId(), p.organizationId(), p.email(), admin);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(fresh, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
