package qaengine.core.evidence;

/** A stored artifact, or the record of why it was dropped. */
public record EvidenceRef(EvidenceType type, int step, String sha256, long size, String location, Status status) {

    public enum Status { STORED, REDACTION_FAILED, TOO_LARGE, RUN_CAP_REACHED }

    public static EvidenceRef dropped(EvidenceType type, int step, Status status) {
        return new EvidenceRef(type, step, null, 0, null, status);
    }
}
