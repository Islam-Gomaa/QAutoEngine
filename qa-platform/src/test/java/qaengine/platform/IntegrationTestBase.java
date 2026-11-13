package qaengine.platform;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL 18, because the queue and constraints are what is being tested. */
@SpringBootTest
@Import(IntegrationTestBase.Containers.class)
@TestPropertySource(properties = {"qa.evidence.root=${java.io.tmpdir}/qa-evidence-test", "spring.task.scheduling.enabled=false"})
public abstract class IntegrationTestBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18-alpine");
        }
    }
}
