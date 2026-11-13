package qaengine.core.evidence;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * The single path evidence takes. Text is redacted first, and if redaction fails the artifact is
 * dropped rather than stored raw.
 */
public final class EvidenceCollector {

    private final EvidenceSink sink;
    private final Redactor redactor;
    private final long runCapBytes;
    private long used;

    public EvidenceCollector(EvidenceSink sink, Redactor redactor, long runCapBytes) {
        this.sink = sink;
        this.redactor = redactor;
        this.runCapBytes = runCapBytes;
    }

    public EvidenceRef text(EvidenceType type, int step, Supplier<String> content) {
        byte[] bytes;
        try {
            bytes = redactor.redact(content.get()).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return EvidenceRef.dropped(type, step, EvidenceRef.Status.REDACTION_FAILED);
        }
        return store(type, step, bytes);
    }

    /** Binary artifacts must already be masked by the capture step. */
    public EvidenceRef binary(EvidenceType type, int step, byte[] content) {
        return store(type, step, content);
    }

    private EvidenceRef store(EvidenceType type, int step, byte[] bytes) {
        if (bytes.length > type.maxBytes) return EvidenceRef.dropped(type, step, EvidenceRef.Status.TOO_LARGE);
        if (used + bytes.length > runCapBytes) return EvidenceRef.dropped(type, step, EvidenceRef.Status.RUN_CAP_REACHED);
        used += bytes.length;
        return sink.store(type, step, bytes);
    }
}
