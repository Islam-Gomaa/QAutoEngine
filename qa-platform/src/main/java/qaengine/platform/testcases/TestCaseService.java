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

    /**
     * What a run can execute, as a condition on test_case c: a case recorded by a run, or one a person approved
     * (READY) whose latest version carries a script. Nothing waiting for review, rejected or archived ever runs.
     */
    public static final String RUNNABLE = """
            (c.archived_at IS NULL AND (c.source = 'AUTOMATED' OR (c.status = 'READY' AND EXISTS (
                SELECT 1 FROM test_case_version sv WHERE sv.organization_id = c.organization_id AND sv.test_case_id = c.id AND sv.script IS NOT NULL
                  AND sv.version = (SELECT max(mv.version) FROM test_case_version mv WHERE mv.organization_id = c.organization_id AND mv.test_case_id = c.id)))))""";

    private final JdbcClient db;

    public TestCaseService(JdbcClient db) {
        this.db = db;
    }

    /** @param stepsJson ordered steps with their locators, as recorded */
    public Saved save(UUID org, UUID project, String naturalKey, String title, String stepsJson) {
        return save(org, project, naturalKey, title, stepsJson, null, null);
    }

    /**
     * @param scriptJson an executable script, already validated, or null
     * @param executionMode READ_ONLY or VALIDATION_ONLY with a script, null without one
     */
    public Saved save(UUID org, UUID project, String naturalKey, String title, String stepsJson, String scriptJson, String executionMode) {
        UUID caseId = db.sql("""
                INSERT INTO test_case (organization_id, id, project_id, title, natural_key) VALUES (?,?,?,?,?)
                ON CONFLICT (organization_id, project_id, natural_key) DO UPDATE SET title = EXCLUDED.title
                RETURNING id
                """).params(org, UUID.randomUUID(), project, title, naturalKey).query(UUID.class).single();
        return version(org, caseId, stepsJson, scriptJson, executionMode);
    }

    /** A new version when the steps or the script changed; the same one when neither did. */
    public Saved version(UUID org, UUID caseId, String stepsJson, String scriptJson, String executionMode) {
        // A case without a script hashes as it always did, so existing versions are still recognized.
        String hash = hash(scriptJson == null ? stepsJson : stepsJson + "\u0000" + executionMode + "\u0000" + scriptJson);
        var existing = db.sql("SELECT id FROM test_case_version WHERE organization_id = ? AND test_case_id = ? AND content_hash = ?")
                .params(org, caseId, hash).query(UUID.class).optional();
        if (existing.isPresent()) return new Saved(caseId, existing.get());
        UUID version = UUID.randomUUID();
        db.sql("""
                INSERT INTO test_case_version (organization_id, id, test_case_id, version, steps, content_hash, script, execution_mode)
                SELECT ?, ?, ?, coalesce(max(version), 0) + 1, ?::jsonb, ?, ?::jsonb, ?
                FROM test_case_version WHERE organization_id = ? AND test_case_id = ?
                """).params(org, version, caseId, stepsJson, hash, scriptJson, executionMode, org, caseId).update();
        return new Saved(caseId, version);
    }

    public void recordResult(UUID org, UUID run, UUID version, String verdict) {
        recordResult(org, run, version, verdict, null, "");
    }

    /** @param failureClass why a scripted case did not pass, or null */
    public void recordResult(UUID org, UUID run, UUID version, String verdict, String failureClass, String summary) {
        db.sql("""
                INSERT INTO test_result (organization_id, run_id, test_case_version_id, verdict, failure_class, summary) VALUES (?,?,?,?,?,?)
                ON CONFLICT (organization_id, run_id, test_case_version_id) DO UPDATE SET verdict = EXCLUDED.verdict,
                    failure_class = EXCLUDED.failure_class, summary = EXCLUDED.summary
                """).params(org, run, version, verdict, failureClass, summary == null ? "" : summary).update();
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
