package qaengine.core.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;

class EvidenceTest {

    static final String CANARY = "canary-Secret-Value-83";
    final Redactor redactor = new Redactor(List.of(CANARY));

    /** Credential-shaped samples are assembled here, so the repository secret scan stays strict. */
    static java.util.stream.Stream<String> samples() {
        String jwtHead = "eyJ" + "hbGciOiJIUzI1NiJ9";
        String jwtBody = "eyJ" + "zdWIiOiIxMjM0In0";
        return java.util.stream.Stream.of(
                "login failed for canary-Secret-Value-83",
                "password=hunter2xyz&next=1", "\"token\": \"abc123def\"", "Authorization: Bearer abcdefgh12345678",
                "jwt " + jwtHead + "." + jwtBody + ".abcdefghijk", "api_key=s" + "k-abcdefghijklmnopqrstuvwx",
                "card 4111 1111 1111 1111 ok", "Cookie: session=abc123def456");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("samples")
    void sensitiveValuesAreRemoved(String input) {
        String out = redactor.redact(input);
        assertTrue(out.contains(Redactor.MASK), out);
        for (String v : List.of(CANARY, "hunter2xyz", "abc123def", "abcdefgh12345678", "abcdefghijk", "4111 1111")) assertFalse(out.contains(v), out);
    }

    @Test void ordinaryTextIsKept() {
        String text = "Order 1234 shipped to room 5, total=20";
        assertEquals(text, redactor.redact(text));
    }

    @Test void redactionFailureDropsTheArtifact(@TempDir Path dir) throws Exception {
        var c = new EvidenceCollector(new LocalEvidenceSink(dir), redactor, 1000);
        var ref = c.text(EvidenceType.SIGNAL_LOG, 1, () -> { throw new IllegalStateException("boom"); });
        assertEquals(EvidenceRef.Status.REDACTION_FAILED, ref.status());
        try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
    }

    @Test void storedTextIsRedactedAndHashed(@TempDir Path dir) throws Exception {
        var c = new EvidenceCollector(new LocalEvidenceSink(dir), redactor, 1000);
        var ref = c.text(EvidenceType.SIGNAL_LOG, 1, () -> "x " + CANARY);
        byte[] stored = Files.readAllBytes(Path.of(ref.location()));
        assertFalse(new String(stored).contains(CANARY));
        assertEquals(LocalEvidenceSink.sha256(stored), ref.sha256());
    }

    @Test void sizeLimitsAreEnforced(@TempDir Path dir) {
        var c = new EvidenceCollector(new LocalEvidenceSink(dir), redactor, Long.MAX_VALUE);
        assertEquals(EvidenceRef.Status.TOO_LARGE, c.binary(EvidenceType.SCREENSHOT, 1, new byte[(int) EvidenceType.SCREENSHOT.maxBytes + 1]).status());
    }

    @Test void runCapIsEnforced(@TempDir Path dir) {
        var c = new EvidenceCollector(new LocalEvidenceSink(dir), redactor, 10);
        assertEquals(EvidenceRef.Status.STORED, c.binary(EvidenceType.SCREENSHOT, 1, new byte[8]).status());
        assertEquals(EvidenceRef.Status.RUN_CAP_REACHED, c.binary(EvidenceType.SCREENSHOT, 2, new byte[8]).status());
    }
}
