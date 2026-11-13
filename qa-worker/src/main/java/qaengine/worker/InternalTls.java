package qaengine.worker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Mutual TLS to the platform. The worker trusts only the internal CA, not the system store, and
 * presents its own certificate. Hostname checking stays on, so a rogue server cannot stand in.
 */
final class InternalTls {

    private InternalTls() {}

    static SSLContext load(Path dir) throws IOException, GeneralSecurityException {
        char[] password = Files.readString(dir.resolve("password")).trim().toCharArray();
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store(dir.resolve("worker.p12"), password), password);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store(dir.resolve("truststore.p12"), password));
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private static KeyStore store(Path file, char[] password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, password);
        }
        return store;
    }
}
