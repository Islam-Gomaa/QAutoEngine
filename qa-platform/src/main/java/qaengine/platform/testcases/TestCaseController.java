package qaengine.platform.testcases;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/test-cases")
public class TestCaseController {

    public record CaseView(UUID id, String title, int latestVersion, String lastVerdict) {}
    public record VersionView(int version, String steps, String contentHash) {}

    private final JdbcClient db;
    private final Access access;

    public TestCaseController(JdbcClient db, Access access) {
        this.db = db;
        this.access = access;
    }

    @GetMapping
    public List<CaseView> list(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.VIEWER);
        return db.sql("""
                SELECT c.id, c.title, coalesce(max(v.version), 0),
                       (SELECT r.verdict FROM test_result r
                        JOIN test_case_version rv ON rv.organization_id = r.organization_id AND rv.id = r.test_case_version_id
                        JOIN test_run tr ON tr.organization_id = r.organization_id AND tr.id = r.run_id
                        WHERE rv.organization_id = c.organization_id AND rv.test_case_id = c.id
                        ORDER BY tr.created_at DESC LIMIT 1)
                FROM test_case c
                LEFT JOIN test_case_version v ON v.organization_id = c.organization_id AND v.test_case_id = c.id
                WHERE c.organization_id = ? AND c.project_id = ?
                GROUP BY c.organization_id, c.id, c.title ORDER BY c.title LIMIT 1000
                """).params(p.organizationId(), projectId)
                .query((rs, n) -> new CaseView(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4))).list();
    }

    @GetMapping("/{caseId}/versions")
    public List<VersionView> versions(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID caseId) {
        access.require(p, projectId, Access.Level.VIEWER);
        boolean exists = db.sql("SELECT count(*) FROM test_case WHERE organization_id = ? AND project_id = ? AND id = ?")
                .params(p.organizationId(), projectId, caseId).query(Long.class).single() == 1;
        if (!exists) throw new NotFoundException("Test case");
        return db.sql("SELECT version, steps::text, content_hash FROM test_case_version WHERE organization_id = ? AND test_case_id = ? ORDER BY version DESC")
                .params(p.organizationId(), caseId).query((rs, n) -> new VersionView(rs.getInt(1), rs.getString(2), rs.getString(3))).list();
    }
}
