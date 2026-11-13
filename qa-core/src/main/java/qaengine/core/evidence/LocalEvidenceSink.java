package qaengine.core.evidence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stores artifacts on disk, named by content hash. Used by the CLI and in tests. */
public final class LocalEvidenceSink implements EvidenceSink {

    private final Path root;

    public LocalEvidenceSink(Path root) {
        this.root = root;
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public EvidenceRef store(EvidenceType type, int step, byte[] content) {
        String hash = sha256(content);
        Path file = root.resolve(hash + "." + type.extension);
        try {
            Files.createDirectories(root);
            if (!Files.exists(file)) Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new EvidenceRef(type, step, hash, content.length, file.toString(), EvidenceRef.Status.STORED);
    }
}
