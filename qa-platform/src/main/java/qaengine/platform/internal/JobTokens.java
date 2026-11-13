package qaengine.platform.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Short-lived tokens scoped to one job and one attempt, so a leaked token cannot reach anything else. */
@Component
public class JobTokens {

    public record Scope(UUID organizationId, UUID jobId, int attempt) {}

    private static final long TTL_SECONDS = 300;

    private final byte[] key;

    public JobTokens(@Value("${qa.worker-key:}") String workerKey, @Value("${qa.worker-key-file:}") String workerKeyFile) throws java.io.IOException {
        String value = workerKeyFile.isBlank() ? workerKey : java.nio.file.Files.readString(java.nio.file.Path.of(workerKeyFile)).trim();
        this.key = value.getBytes(StandardCharsets.UTF_8);
    }

    public boolean workerKeyMatches(String presented) {
        if (key.length < 32 || presented == null) return false;
        return MessageDigest.isEqual(key, presented.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(Scope s) {
        String body = s.organizationId() + "." + s.jobId() + "." + s.attempt() + "." + (Instant.now().getEpochSecond() + TTL_SECONDS);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "." + sign(body);
    }

    public Optional<Scope> verify(String token) {
        if (token == null || key.length < 32) return Optional.empty();
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) return Optional.empty();
            String body = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) return Optional.empty();
            String[] f = body.split("\\.");
            if (Long.parseLong(f[3]) < Instant.now().getEpochSecond()) return Optional.empty();
            return Optional.of(new Scope(UUID.fromString(f[0]), UUID.fromString(f[1]), Integer.parseInt(f[2])));
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
