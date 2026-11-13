package qaengine.platform.identity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the single organization and first admin from a secret file. There is no default password. */
@Component
public class Bootstrap implements ApplicationRunner {

    private final JdbcClient db;
    private final PasswordEncoder encoder;
    private final String email;
    private final String passwordFile;
    private final String organization;

    public Bootstrap(JdbcClient db, PasswordEncoder encoder,
                     @Value("${qa.bootstrap.admin-email:}") String email,
                     @Value("${qa.bootstrap.admin-password-file:}") String passwordFile,
                     @Value("${qa.bootstrap.organization:Default Organization}") String organization) {
        this.db = db;
        this.encoder = encoder;
        this.email = email;
        this.passwordFile = passwordFile;
        this.organization = organization;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        if (email.isBlank() || passwordFile.isBlank()) return;
        if (db.sql("SELECT count(*) FROM app_user").query(Long.class).single() > 0) return;
        UUID org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        db.sql("INSERT INTO organization (id, name) VALUES (?,?)").params(org, organization).update();
        db.sql("INSERT INTO app_user (id, email, password_hash) VALUES (?,?,?)")
                .params(user, email.toLowerCase(), encoder.encode(Files.readString(Path.of(passwordFile)).trim())).update();
        db.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (?,?, 'ORGANIZATION_ADMIN')").params(org, user).update();
    }
}
