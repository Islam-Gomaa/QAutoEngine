package qaengine.core.evidence;

import java.time.Duration;

/** Limits and default retention per ADR-014. Retention values are provisional until a data policy exists. */
public enum EvidenceType {
    SCREENSHOT("png", "image/png", 10L << 20, Duration.ofDays(90), false),
    DOM_SNAPSHOT("html", "text/html", 5L << 20, Duration.ofDays(90), true),
    SIGNAL_LOG("json", "application/json", 10L << 20, Duration.ofDays(90), true),
    API_EXCHANGE("json", "application/json", 1L << 20, Duration.ofDays(90), true);

    public final String extension;
    public final String mediaType;
    public final long maxBytes;
    public final Duration retention;
    public final boolean text;

    EvidenceType(String extension, String mediaType, long maxBytes, Duration retention, boolean text) {
        this.extension = extension;
        this.mediaType = mediaType;
        this.maxBytes = maxBytes;
        this.retention = retention;
        this.text = text;
    }
}
