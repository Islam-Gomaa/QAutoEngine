package qaengine.core.evidence;

/** Where artifacts go. The worker writes through this, whether to disk or to the platform. */
public interface EvidenceSink {
    EvidenceRef store(EvidenceType type, int step, byte[] content);
}
