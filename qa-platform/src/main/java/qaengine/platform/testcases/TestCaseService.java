package qaengine.platform.testcases;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Test cases are versioned by content. Versions are never edited, so past results stay meaningful. */
@Component
public class TestCaseService {

    public record Saved(UUID testCaseId, UUID versionId) {}

    private final JdbcClient db;

    public TestCaseService(JdbcClient db) {
        this.db = db;
    }

    /** @param stepsJson ordered steps with their locators, as recorded */
    public Saved save(UUID org, UUID project, String naturalKey, String title, String stepsJson) {
        UUID caseId = db.sql("""
                INSERT INTO test_case (organization_id, id, project_id, title, natural_key) VALUES (?,?,?,?,?)
                ON CONFLICT (organization_id, project_id, natural_key) DO UPDATE SET title = EXCLUDED.title
                RETURNING id
                """).params(org, UUID.randomUUID(), project, title, naturalKey).query(UUID.class).single();
        String hash = hash(stepsJson);
        var existing = db.sql("SELECT id FROM test_case_version WHERE organization_id = ? AND test_case_id = ? AND content_hash = ?")
                .params(org, caseId, hash).query(UUID.class).optional();
        if (existing.isPresent()) return new Saved(caseId, existing.get());
        UUID version = UUID.randomUUID();
        db.sql("""
                INSERT INTO test_case_version (organization_id, id, test_case_id, version, steps, content_hash)
                SELECT ?, ?, ?, coalesce(max(version), 0) + 1, ?::jsonb, ?
                FROM test_case_version WHERE organization_id = ? AND test_case_id = ?
                """).params(org, version, caseId, stepsJson, hash, org, caseId).update();
        return new Saved(caseId, version);
    }

    public void recordResult(UUID org, UUID run, UUID version, String verdict) {
        db.sql("""
                INSERT INTO test_result (organization_id, run_id, test_case_version_id, verdict) VALUES (?,?,?,?)
                ON CONFLICT (organization_id, run_id, test_case_version_id) DO UPDATE SET verdict = EXCLUDED.verdict
                """).params(org, run, version, verdict).update();
    }

    public void link(UUID org, UUID defect, UUID testCase) {
        db.sql("INSERT INTO defect_test_case (organization_id, defect_id, test_case_id) VALUES (?,?,?) ON CONFLICT DO NOTHING")
                .params(org, defect, testCase).update();
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
