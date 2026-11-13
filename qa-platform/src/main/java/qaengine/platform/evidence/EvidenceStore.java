package qaengine.platform.evidence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Files on a volume, separated per organization so nothing is shared or deduplicated across tenants. */
@Component
public class EvidenceStore {

    private final Path root;

    public EvidenceStore(@Value("${qa.evidence.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public String write(UUID org, UUID run, String sha256, String extension, byte[] content) {
        String key = org + "/" + run + "/" + sha256 + "." + extension;
        Path file = resolve(key);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "up", ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return key;
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolve(String key) {
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root)) throw new SecurityException("Evidence key escapes the store");
        return file;
    }
}
