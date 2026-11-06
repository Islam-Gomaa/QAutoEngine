package qaengine.platform.testcases;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/suites")
public class SuiteController {

    public record SaveSuite(@NotBlank @Size(max = 120) String name, String description, String module, String channel) {}
    /** @param automatedCases cases a run can execute; manual ones are recorded by a person */
    public record SuiteView(UUID id, String name, String description, String module, String channel, OffsetDateTime archivedAt, long cases, long automatedCases) {}

    private final JdbcClient db;
    private final Access access;
    private final AuditLog audit;

    public SuiteController(JdbcClient db, Access access, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT s.id, s.name, s.description, s.module, s.channel, s.archived_at,
                   (SELECT count(*) FROM test_case c WHERE c.organization_id = s.organization_id AND c.suite_id = s.id AND c.archived_at IS NULL),
                   (SELECT count(*) FROM test_case c WHERE c.organization_id = s.organization_id AND c.suite_id = s.id AND c.archived_at IS NULL AND c.source = 'AUTOMATED')
            FROM test_suite s
            """;

    private static SuiteView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SuiteView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getLong(7), rs.getLong(8));
    }

    @GetMapping
    public List<SuiteView> list(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @RequestParam(defaultValue = "false") boolean archived) {
        access.require(p, projectId, Access.Level.VIEWER);
        return db.sql(SELECT + " WHERE s.organization_id = ? AND s.project_id = ? AND (s.archived_at IS NOT NULL) = ? ORDER BY s.module, s.name")
                .params(p.organizationId(), projectId, archived).query((rs, n) -> map(rs)).list();
    }

    private static String channel(SaveSuite body) {
        String c = body.channel() == null ? "WEB" : body.channel();
        if (!Set.of("WEB", "API").contains(c)) throw new ConflictException("Unknown channel");
        return c;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public SuiteView create(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @Valid @RequestBody SaveSuite body) {
        access.require(p, projectId, Access.Level.QA_LEAD);
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO test_suite (organization_id, id, project_id, name, description, module, channel) VALUES (?,?,?,?,?,?,?)")
                .params(p.organizationId(), id, projectId, body.name().trim(), text(body.description()), text(body.module()), channel(body)).update();
        audit.record(p.organizationId(), projectId, p.email(), "SUITE_CREATED", id.toString(), body.name());
        return get(p, projectId, id);
    }

    private SuiteView get(Principal p, UUID projectId, UUID id) {
        return db.sql(SELECT + " WHERE s.organization_id = ? AND s.project_id = ? AND s.id = ?").params(p.organizationId(), projectId, id)
                .query((rs, n) -> map(rs)).optional().orElseThrow(() -> new NotFoundException("Suite"));
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    @PatchMapping("/{suiteId}")
    @Transactional
    public SuiteView update(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID suiteId, @Valid @RequestBody SaveSuite body) {
        access.require(p, projectId, Access.Level.QA_LEAD);
        get(p, projectId, suiteId);
        db.sql("UPDATE test_suite SET name = ?, description = ?, module = ?, channel = ? WHERE organization_id = ? AND id = ?")
                .params(body.name().trim(), text(body.description()), text(body.module()), channel(body), p.organizationId(), suiteId).update();
        audit.record(p.organizationId(), projectId, p.email(), "SUITE_UPDATED", suiteId.toString(), body.name());
        return get(p, projectId, suiteId);
    }

    @PostMapping("/{suiteId}/archive")
    @Transactional
    public void archive(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID suiteId) {
        access.require(p, projectId, Access.Level.QA_LEAD);
        get(p, projectId, suiteId);
        db.sql("UPDATE test_suite SET archived_at = now() WHERE organization_id = ? AND id = ?").params(p.organizationId(), suiteId).update();
        audit.record(p.organizationId(), projectId, p.email(), "SUITE_ARCHIVED", suiteId.toString(), "");
    }

    @PostMapping("/{suiteId}/restore")
    @Transactional
    public void restore(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID suiteId) {
        access.require(p, projectId, Access.Level.QA_LEAD);
        get(p, projectId, suiteId);
        db.sql("UPDATE test_suite SET archived_at = NULL WHERE organization_id = ? AND id = ?").params(p.organizationId(), suiteId).update();
        audit.record(p.organizationId(), projectId, p.email(), "SUITE_RESTORED", suiteId.toString(), "");
    }
}
