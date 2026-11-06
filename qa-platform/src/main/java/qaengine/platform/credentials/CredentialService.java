package qaengine.platform.credentials;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import qaengine.contracts.WorkerContracts.LoginSecret;
import qaengine.platform.identity.SecretBox;
import qaengine.platform.identity.Totp;

/** Values go in and are never read back by any user-facing API. */
@Component
public class CredentialService {

    /** @param totpSeed base64 seed of an organization-issued test account */
    public record Material(String username, String password, String totpSeed, String cookieName, String cookieValue, String lowPrivilegeToken) {}

    private final JdbcClient db;
    private final SecretBox box;
    private final ObjectMapper json;

    public CredentialService(JdbcClient db, SecretBox box, ObjectMapper json) {
        this.db = db;
        this.box = box;
        this.json = json;
    }

    private static String owner(UUID org, UUID id) {
        return org + "/" + id;
    }

    public UUID store(UUID org, UUID project, String name, Material material) {
        UUID id = UUID.randomUUID();
        var sealed = box.seal(json.writeValueAsString(material), owner(org, id));
        db.sql("INSERT INTO credential (organization_id, id, project_id, name, wrapped_key, ciphertext) VALUES (?,?,?,?,?,?)")
                .params(org, id, project, name, sealed.wrappedKey(), sealed.ciphertext()).update();
        return id;
    }

    public void replace(UUID org, UUID id, Material material) {
        var sealed = box.seal(json.writeValueAsString(material), owner(org, id));
        db.sql("UPDATE credential SET wrapped_key = ?, ciphertext = ? WHERE organization_id = ? AND id = ?")
                .params(sealed.wrappedKey(), sealed.ciphertext(), org, id).update();
    }

    /** The seed stays on the server. Only a current code is released. */
    public LoginSecret release(UUID org, UUID id) {
        var sealed = db.sql("SELECT wrapped_key, ciphertext FROM credential WHERE organization_id = ? AND id = ?").params(org, id)
                .query((rs, n) -> new SecretBox.Sealed(rs.getBytes(1), rs.getBytes(2))).single();
        Material m = json.readValue(box.open(sealed, owner(org, id)), Material.class);
        String code = m.totpSeed() == null ? null : Totp.code(Base64.getDecoder().decode(m.totpSeed()), Instant.now().getEpochSecond() / 30);
        return new LoginSecret(m.username(), m.password(), code, m.cookieName(), m.cookieValue(), m.lowPrivilegeToken());
    }
}
