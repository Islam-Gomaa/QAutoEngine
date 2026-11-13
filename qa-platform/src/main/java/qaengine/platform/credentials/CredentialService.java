package qaengine.platform.credentials;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import qaengine.contracts.WorkerContracts.LoginSecret;
import qaengine.platform.identity.SecretStore;
import qaengine.platform.identity.Totp;

/** Values go in and are never read back by any user-facing API. */
@Component
public class CredentialService {

    /** @param totpSeed base64 seed of an organization-issued test account */
    public record Material(String username, String password, String totpSeed, String cookieName, String cookieValue, String lowPrivilegeToken) {

        /** Names of the values that are present. Never the values. */
        List<String> fields() {
            List<String> out = new ArrayList<>();
            if (present(username)) out.add("username");
            if (present(password)) out.add("password");
            if (present(totpSeed)) out.add("totpSeed");
            if (present(cookieName)) out.add("cookieName");
            if (present(cookieValue)) out.add("cookieValue");
            if (present(lowPrivilegeToken)) out.add("lowPrivilegeToken");
            return out;
        }

        /** These values, with anything left blank taken from {@code current}: an update never erases a secret by omission. */
        Material over(Material current) {
            return new Material(pick(username, current.username), pick(password, current.password), pick(totpSeed, current.totpSeed),
                    pick(cookieName, current.cookieName), pick(cookieValue, current.cookieValue), pick(lowPrivilegeToken, current.lowPrivilegeToken));
        }

        private static String pick(String given, String current) {
            return present(given) ? given : current;
        }
    }

    static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private final JdbcClient db;
    private final SecretStore box;
    private final ObjectMapper json;

    public CredentialService(JdbcClient db, SecretStore box, ObjectMapper json) {
        this.db = db;
        this.box = box;
        this.json = json;
    }

    private static String owner(UUID org, UUID id) {
        return org + "/" + id;
    }

    /**
     * A masked username for display: first and last character only, so an email address does not
     * show its domain either. Short names show nothing.
     */
    static String hint(String username) {
        if (!present(username)) return "";
        int[] cp = username.strip().codePoints().toArray();
        if (cp.length <= 3) return "•••";
        return new String(cp, 0, 1) + "•••" + new String(cp, cp.length - 1, 1);
    }

    /** @param environment the one environment this credential may be used in; null leaves it unbound until first use */
    public UUID store(UUID org, UUID project, UUID environment, String name, Material material) {
        UUID id = UUID.randomUUID();
        var sealed = box.seal(json.writeValueAsString(material), owner(org, id));
        db.sql("""
                INSERT INTO credential (organization_id, id, project_id, environment_id, name, wrapped_key, ciphertext, username_hint, secret_fields)
                VALUES (?,?,?,?,?,?,?,?,?)
                """).params(org, id, project, environment, name, sealed.wrappedKey(), sealed.ciphertext(), hint(material.username()),
                material.fields().toArray(String[]::new)).update();
        return id;
    }

    /** Applies the values given and keeps the rest. Returns the names of the values that changed. */
    public List<String> update(UUID org, UUID id, Material changes) {
        Material current = open(org, id);
        Material next = changes.over(current);
        List<String> changed = changes.fields();
        if (changed.isEmpty()) return changed;
        var sealed = box.seal(json.writeValueAsString(next), owner(org, id));
        db.sql("UPDATE credential SET wrapped_key = ?, ciphertext = ?, username_hint = ?, secret_fields = ?, updated_at = now() WHERE organization_id = ? AND id = ?")
                .params(sealed.wrappedKey(), sealed.ciphertext(), hint(next.username()), next.fields().toArray(String[]::new), org, id).update();
        return changed;
    }

    private Material open(UUID org, UUID id) {
        var sealed = db.sql("SELECT wrapped_key, ciphertext FROM credential WHERE organization_id = ? AND id = ?").params(org, id)
                .query((rs, n) -> new SecretStore.Sealed(rs.getBytes(1), rs.getBytes(2))).single();
        return json.readValue(box.open(sealed, owner(org, id)), Material.class);
    }

    /** The seed stays on the server. Only a current code is released. */
    public LoginSecret release(UUID org, UUID id) {
        Material m = open(org, id);
        String code = m.totpSeed() == null ? null : Totp.code(Base64.getDecoder().decode(m.totpSeed()), Instant.now().getEpochSecond() / 30);
        return new LoginSecret(m.username(), m.password(), code, m.cookieName(), m.cookieValue(), m.lowPrivilegeToken());
    }
}
