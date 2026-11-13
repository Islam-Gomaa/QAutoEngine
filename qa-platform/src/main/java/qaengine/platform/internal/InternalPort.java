package qaengine.platform.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
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
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalConnector(
            @Value("${qa.internal-port:0}") int port,
            @Value("${qa.internal-tls.certificate:}") String certificate,
            @Value("${qa.internal-tls.key:}") String key,
            @Value("${qa.internal-tls.truststore:}") String truststore,
            @Value("${qa.internal-tls.password-file:}") String passwordFile) {
        return factory -> {
            if (port <= 0) return;
            if (certificate.isBlank()) throw new IllegalStateException("The internal port needs mutual TLS");
            factory.addAdditionalConnectors(mutualTlsConnector(port, certificate, key, truststore, passwordFile));
        };
    }

    /** Workers must present a certificate from the internal CA, or the handshake fails. */
    private static Connector mutualTlsConnector(int port, String certificate, String key, String truststore, String passwordFile) {
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        connector.setScheme("https");
        connector.setSecure(true);
        var protocol = (Http11NioProtocol) connector.getProtocolHandler();
        protocol.setSSLEnabled(true);
        SSLHostConfig tls = new SSLHostConfig();
        tls.setProtocols("TLSv1.3");
        tls.setCertificateVerification("required");
        tls.setTruststoreFile(truststore);
        tls.setTruststoreType("PKCS12");
        try {
            tls.setTruststorePassword(java.nio.file.Files.readString(java.nio.file.Path.of(passwordFile)).trim());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        SSLHostConfigCertificate serverCertificate = new SSLHostConfigCertificate(tls, SSLHostConfigCertificate.Type.UNDEFINED);
        serverCertificate.setCertificateFile(certificate);
        serverCertificate.setCertificateKeyFile(key);
        tls.addCertificate(serverCertificate);
        connector.addSslHostConfig(tls);
        return connector;
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
