package qaengine.platform.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Worker traffic has its own port. The two surfaces refuse each other's paths. */
@Configuration
public class InternalPort {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalConnector(@Value("${qa.internal-port:0}") int port) {
        return factory -> {
            if (port <= 0) return;
            Connector connector = new Connector();
            connector.setPort(port);
            factory.addAdditionalConnectors(connector);
        };
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> portSeparation(@Value("${qa.internal-port:0}") int port) {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                boolean internalPath = request.getRequestURI().startsWith("/internal/");
                boolean internalPort = request.getLocalPort() == port;
                if (port > 0 && internalPath != internalPort) {
                    // setStatus, not sendError: an error dispatch would be answered by the security chain instead.
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                chain.doFilter(request, response);
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
