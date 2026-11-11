package qaengine.platform;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/** On a real connection: a worker's oversized or chunked body is answered before any of it is sent or read. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"qa.worker-key=test-worker-key-0123456789abcdef0123456789", "qa.master-key-file=${java.io.tmpdir}/qa-test-master.key"})
class RequestSizeLimitTest extends IntegrationTestBase {

    @LocalServerPort int port;

    static {
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "qa-test-master.key"), java.util.Base64.getEncoder().encodeToString(new byte[32]));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Sends only the request head, then reads the status line. */
    String statusFor(String head) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        }
    }

    @Test void aDeclaredFiftyMegabyteBodyIsRefusedBeforeAnyOfItArrives() throws Exception {
        String status = statusFor("POST /internal/v1/jobs/" + UUID.randomUUID() + "/steps HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer forged\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + (50L << 20) + "\r\n\r\n");
        assertTrue(status.startsWith("HTTP/1.1 413"), status);
    }

    @Test void aChunkedBodyIsRefused() throws Exception {
        String status = statusFor("POST /internal/v1/jobs/" + UUID.randomUUID() + "/steps HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer forged\r\n"
                + "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n");
        assertTrue(status.startsWith("HTTP/1.1 411"), status);
    }

    @Test void aSmallBodyGoesOnToAuthentication() throws Exception {
        String status = statusFor("POST /internal/v1/jobs/" + UUID.randomUUID() + "/steps HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer forged\r\n"
                + "Content-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]");
        assertTrue(status.startsWith("HTTP/1.1 401"), status);
    }
}
