package qaengine.platform.evidence;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import qaengine.core.evidence.EvidenceType;
import qaengine.core.evidence.LocalEvidenceSink;
import qaengine.core.evidence.Redactor;
import qaengine.platform.shared.ConflictException;

/** Upload checks, with the rescan as a second line behind the worker's redaction. */
@Component
public class EvidenceService {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final Duration LINKED_AFTER_CLOSE = Duration.ofDays(180);

    private final JdbcClient db;
    private final EvidenceStore store;
    private final Redactor rescan = new Redactor(List.of());

    public EvidenceService(JdbcClient db, EvidenceStore store) {
        this.db = db;
        this.store = store;
    }

    /** Idempotent per attempt, step and hash, so a retried upload creates no duplicate. */
    public UUID ingest(UUID org, UUID run, int attempt, int step, EvidenceType type, String claimedSha256, byte[] content) {
        if (content.length > type.maxBytes) throw new ConflictException("Evidence too large");
        if (!LocalEvidenceSink.sha256(content).equals(claimedSha256)) throw new ConflictException("Evidence hash mismatch");
        if (type == EvidenceType.SCREENSHOT && !isPng(content)) throw new ConflictException("Screenshot is not a PNG");

        byte[] stored = content;
        if (type.text) stored = rescan.redact(new String(content, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        String storedHash = LocalEvidenceSink.sha256(stored);
        String key = store.write(org, run, storedHash, type.extension, stored);
        return db.sql("""
                INSERT INTO evidence (organization_id, id, run_id, attempt, step, type, sha256, size, storage_key, expires_at)
                VALUES (?,?,?,?,?,?,?,?,?, now() + make_interval(days => ?))
                ON CONFLICT (organization_id, run_id, attempt, step, sha256) DO UPDATE SET size = EXCLUDED.size
                RETURNING id
                """).params(org, UUID.randomUUID(), run, attempt, step, type.name(), storedHash, (long) stored.length, key, (int) type.retention.toDays())
                .query(UUID.class).single();
    }

    private static boolean isPng(byte[] content) {
        return content.length > 4 && Arrays.equals(Arrays.copyOf(content, 4), PNG);
    }

    /**
     * Removes expired files, keeping a tombstone. Evidence on an open defect, or on a defect closed
     * less than 180 days ago, is kept, as is anything on legal hold.
     */
    public int deleteExpired() {
        record Row(UUID org, UUID id, String key) {}
        List<Row> rows = db.sql("""
                SELECT e.organization_id, e.id, e.storage_key FROM evidence e
                WHERE e.deleted_at IS NULL AND e.expires_at < now() AND NOT e.legal_hold
                  AND NOT EXISTS (
                    SELECT 1 FROM finding_evidence fe
                    JOIN finding f ON f.organization_id = fe.organization_id AND f.id = fe.finding_id
                    JOIN defect d ON d.organization_id = f.organization_id AND d.id = f.defect_id
                    WHERE fe.organization_id = e.organization_id AND fe.evidence_id = e.id
                      AND (d.closed_at IS NULL OR d.closed_at > now() - make_interval(days => ?)))
                LIMIT 1000
                """).param((int) LINKED_AFTER_CLOSE.toDays())
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3))).list();
        for (Row r : rows) {
            boolean shared = db.sql("SELECT count(*) FROM evidence WHERE organization_id = ? AND storage_key = ? AND deleted_at IS NULL AND id <> ?")
                    .params(r.org(), r.key(), r.id()).query(Long.class).single() > 0;
            if (!shared) store.delete(r.key());
            db.sql("UPDATE evidence SET deleted_at = now() WHERE organization_id = ? AND id = ?").params(r.org(), r.id()).update();
        }
        return rows.size();
    }

    /** Re-hashes a random sample. Returns the ids that no longer match. */
    public List<UUID> verifySample(int size) {
        record Row(UUID id, String sha, String key) {}
        return db.sql("SELECT id, sha256, storage_key FROM evidence WHERE deleted_at IS NULL ORDER BY random() LIMIT ?").param(size)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list().stream()
                .filter(r -> {
                    try {
                        return !LocalEvidenceSink.sha256(store.read(r.key())).equals(r.sha());
                    } catch (RuntimeException e) {
                        return true;
                    }
                }).map(Row::id).toList();
    }
}
