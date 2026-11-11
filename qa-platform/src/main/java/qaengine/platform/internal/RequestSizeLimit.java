package qaengine.platform.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import qaengine.core.evidence.EvidenceType;

/**
 * Workers handle hostile pages, so a worker may be compromised. Its requests are refused by size before any of
 * the body is read: the gateway only forwards TCP, and nothing else limits what a worker can send.
 */
@Configuration
public class RequestSizeLimit {

    /** Evidence files: the largest any evidence type allows. The type's own limit is checked again on ingest. */
    static final long EVIDENCE_MAX_BYTES = Arrays.stream(EvidenceType.values()).mapToLong(t -> t.maxBytes).max().orElseThrow();
    /** Everything else is JSON. The largest real message, a discovered page, is a small fraction of this. */
    static final long JSON_MAX_BYTES = 2L << 20;

    static long limitFor(String path) {
        return path.endsWith("/evidence") ? EVIDENCE_MAX_BYTES : JSON_MAX_BYTES;
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> internalRequestSize() {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !request.getRequestURI().startsWith("/internal/");
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                // With neither a length nor chunked encoding there is no body at all. A chunked body has no length
                // to judge before it is read, so it is refused: workers always send a length.
                long declared = request.getContentLengthLong();
                if (request.getHeader("Transfer-Encoding") != null) {
                    response.setStatus(HttpServletResponse.SC_LENGTH_REQUIRED);
                    return;
                }
                if (declared > limitFor(request.getRequestURI())) {
                    // setStatus, not sendError: an error dispatch would be answered by the security chain instead.
                    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                    response.setHeader("Connection", "close");
                    return;
                }
                chain.doFilter(request, response);
            }
        });
        // Right after port separation, before authentication and before anything reads the body.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
