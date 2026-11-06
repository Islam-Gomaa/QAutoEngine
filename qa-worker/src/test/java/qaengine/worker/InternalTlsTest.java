package qaengine.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The worker side of the transport: it must refuse any server it cannot verify. */
class InternalTlsTest {

    static final String PASSWORD = "test-password";
    @TempDir static Path dir;

    static void keytool(String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        command.addAll(List.of(args));
        var p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), String.join(" ", args));
    }

    static void pair(String name, String cn) throws Exception {
        keytool("-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + cn, "-ext", "san=dns:localhost",
                "-validity", "1", "-keystore", dir.resolve(name + ".p12").toString(), "-storetype", "PKCS12", "-storepass", PASSWORD);
    }

    @BeforeAll
    static void keys() throws Exception {
        pair("trusted", "localhost");
        pair("rogue", "localhost");
        pair("worker", "worker");
        keytool("-exportcert", "-alias", "trusted", "-keystore", dir.resolve("trusted.p12").toString(), "-storepass", PASSWORD, "-rfc", "-file", dir.resolve("trusted.crt").toString());
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", dir.resolve("trusted.crt").toString(), "-keystore", dir.resolve("truststore.p12").toString(), "-storetype", "PKCS12", "-storepass", PASSWORD);
        Files.writeString(dir.resolve("password"), PASSWORD);
    }

    static HttpsServer server(String keystore, AtomicInteger hits) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(dir.resolve(keystore))) {
            store.load(in, PASSWORD.toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD.toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);
        HttpsServer s = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.setHttpsConfigurator(new HttpsConfigurator(tls));
        s.createContext("/", x -> { hits.incrementAndGet(); x.sendResponseHeaders(204, -1); x.close(); });
        s.start();
        return s;
    }

    @Test
    void workerConnectsToAServerFromItsCa() throws Exception {
        var hits = new AtomicInteger();
        var s = server("trusted.p12", hits);
        try {
            var client = new PlatformClient("https://localhost:" + s.getAddress().getPort(), "k", InternalTls.load(dir));
            assertTrue(client.claim("w").isEmpty());
            assertEquals(1, hits.get());
        } finally {
            s.stop(0);
        }
    }

    @Test
    void workerRefusesARogueServerAndSendsNothing() throws Exception {
        var hits = new AtomicInteger();
        var s = server("rogue.p12", hits);
        try {
            var client = new PlatformClient("https://localhost:" + s.getAddress().getPort(), "secret-worker-key", InternalTls.load(dir));
            assertThrows(IOException.class, () -> client.claim("w"));
            assertEquals(0, hits.get(), "no request may reach a server the worker cannot verify");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void workerRefusesAPlainHttpPlatform() {
        assertThrows(SecurityException.class, () -> new PlatformClient("http://gateway:8081", "k", InternalTls.load(dir)));
    }
}
