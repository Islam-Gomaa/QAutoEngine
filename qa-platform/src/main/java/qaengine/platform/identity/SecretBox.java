package qaengine.platform.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Envelope encryption (ADR-017). Each value gets its own data key, wrapped by a master key that is
 * read from a Docker secret file and is never stored in the database.
 */
@Component
public class SecretBox implements SecretStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey master;

    public SecretBox(@Value("${qa.master-key-file:}") String masterKeyFile) {
        this.master = masterKeyFile.isBlank() ? null : load(Path.of(masterKeyFile));
    }

    private static SecretKey load(Path file) {
        try {
            byte[] key = Base64.getDecoder().decode(Files.readString(file).trim());
            if (key.length != 32) throw new IllegalStateException("Master key must be 256 bits");
            return new SecretKeySpec(key, "AES");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SecretKey master() {
        if (master == null) throw new IllegalStateException("No master key configured");
        return master;
    }

    @Override
    public Sealed seal(String value, String owner) {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            SecretKey dataKey = generator.generateKey();
            byte[] cipher = encrypt(dataKey, value.getBytes(java.nio.charset.StandardCharsets.UTF_8), owner);
            return new Sealed(encrypt(master(), dataKey.getEncoded(), owner), cipher);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String open(Sealed sealed, String owner) {
        try {
            byte[] dataKey = decrypt(master(), sealed.wrappedKey(), owner);
            return new String(decrypt(new SecretKeySpec(dataKey, "AES"), sealed.ciphertext(), owner), java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Sealed value could not be opened");
        }
    }

    private static byte[] encrypt(SecretKey key, byte[] plain, String aad) throws GeneralSecurityException {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        c.updateAAD(aad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] out = c.doFinal(plain);
        return ByteBuffer.allocate(iv.length + out.length).put(iv).put(out).array();
    }

    private static byte[] decrypt(SecretKey key, byte[] sealed, String aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
        c.updateAAD(aad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return c.doFinal(sealed, 12, sealed.length - 12);
    }
}
