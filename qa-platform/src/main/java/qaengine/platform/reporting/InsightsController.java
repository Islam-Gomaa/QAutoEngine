package qaengine.platform.reporting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.Principal;

/** Reports and analytics across the projects a caller can see. Everything is computed from stored rows. */
@RestController
@RequestMapping("/api/v1")
public class InsightsController {

    public record Execution(long runs, long succeeded, long failed, long cancelled, long active, long passed, long failedSteps, long blocked, long inconclusive, Double passRate) {}
    public record ProjectQuality(String project, long runs, Double passRate, long openDefects, long criticalDefects, long fixedDefects, long testCases) {}
    public record Coverage(String project, long total, long automated, long manual, long ready, long draft, long deprecated, long inSuite, long executed) {}
    public record Count(String name, long count) {}
    public record Defects(List<Count> bySeverity, List<Count> byStatus, List<Count> byType) {}
    public record Channel(String channel, long runs, long passed, long failed, long blocked, Double passRate, long defects) {}
    public record Trend(LocalDate day, long runs, long passed, long failed, Double passRate, long defectsOpened, long defectsClosed) {}
    public record Overview(int days, Execution execution, List<ProjectQuality> projects, List<Coverage> coverage, Defects defects, List<Channel> channels, List<Trend> trend) {}
    public record Hotspot(String name, String project, long failures, long total) {}
    public record SlowOperation(String operation, String project, long calls, long averageMs, long maxMs) {}
    public record Analytics(int days, List<Trend> trend, List<Hotspot> hotspots, List<SlowOperation> slowest, List<Count> defectTypes) {}

    private static final String RUNS = """
            WITH runs AS (
                SELECT r.organization_id, r.id, r.project_id, r.status, r.created_at, e.channel,
                       (SELECT max(x.attempt) FROM run_step x WHERE x.organization_id = r.organization_id AND x.run_id = r.id) AS attempt
                FROM test_run r JOIN environment e ON e.organization_id = r.organization_id AND e.id = r.environment_id
                WHERE r.organization_id = :org AND r.created_at >= current_date - (:days - 1) AND r.project_id IN %s
            ), steps AS (
                SELECT runs.id AS run_id, runs.project_id, runs.created_at, runs.channel, s.verdict, s.url, s.label, s.kind, s.status, s.duration_ms
                FROM runs JOIN run_step s ON s.organization_id = runs.organization_id AND s.run_id = runs.id AND s.attempt = runs.attempt
            )
            """.formatted(Access.VISIBLE_PROJECTS);

    private final JdbcClient db;
    private final Access access;
    private final AuditLog audit;

    public InsightsController(JdbcClient db, Access access, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.audit = audit;
    }

    private JdbcClient.StatementSpec bind(String sql, Principal p, UUID project, int days) {
        return db.sql(sql).param("org", p.organizationId()).param("admin", p.organizationAdmin()).param("user", p.userId()).param("project", project).param("days", days);
    }

    private static Double rate(long passed, long failed) {
        return passed + failed == 0 ? null : passed * 100.0 / (passed + failed);
    }

    private static int span(int days) {
        return Math.min(Math.max(days, 1), 365);
    }

    private void narrow(Principal p, UUID project) {
        if (project != null) access.require(p, project, Access.Level.VIEWER);
    }

    private List<Trend> trend(Principal p, UUID project, int days) {
        String visible = Access.VISIBLE_PROJECTS;
        return bind(RUNS + """
                SELECT g.day::date,
                       (SELECT count(*) FROM runs r WHERE r.created_at::date = g.day::date),
                       (SELECT count(*) FROM steps s WHERE s.created_at::date = g.day::date AND s.verdict = 'PASS'),
                       (SELECT count(*) FROM steps s WHERE s.created_at::date = g.day::date AND s.verdict = 'FAIL'),
                       (SELECT count(*) FROM defect d WHERE d.organization_id = :org AND d.project_id IN %1$s AND d.created_at::date = g.day::date),
                       (SELECT count(*) FROM defect d WHERE d.organization_id = :org AND d.project_id IN %1$s AND d.closed_at::date = g.day::date)
                FROM generate_series(current_date - (:days - 1), current_date, interval '1 day') g(day) ORDER BY g.day
                """.formatted(visible), p, project, days)
                .query((rs, n) -> new Trend(rs.getObject(1, LocalDate.class), rs.getLong(2), rs.getLong(3), rs.getLong(4), rate(rs.getLong(3), rs.getLong(4)), rs.getLong(5), rs.getLong(6))).list();
    }

    private List<Count> counts(Principal p, UUID project, String column) {
        return bind("""
                SELECT %s, count(*) FROM defect d WHERE d.organization_id = :org AND d.project_id IN %s GROUP BY 1 ORDER BY 2 DESC, 1
                """.formatted(column, Access.VISIBLE_PROJECTS), p, project, 1).query((rs, n) -> new Count(rs.getString(1), rs.getLong(2))).list();
    }

    @GetMapping("/reports/overview")
    public Overview overview(@AuthenticationPrincipal Principal p, @RequestParam(required = false) UUID projectId, @RequestParam(defaultValue = "30") int days) {
        narrow(p, projectId);
        return build(p, projectId, span(days));
    }

    private Overview build(Principal p, UUID project, int days) {
        String visible = Access.VISIBLE_PROJECTS;
        Execution execution = bind(RUNS + """
                SELECT (SELECT count(*) FROM runs), (SELECT count(*) FROM runs WHERE status = 'SUCCEEDED'), (SELECT count(*) FROM runs WHERE status = 'FAILED'),
                       (SELECT count(*) FROM runs WHERE status = 'CANCELLED'), (SELECT count(*) FROM runs WHERE status IN ('QUEUED','RUNNING')),
                       (SELECT count(*) FROM steps WHERE verdict = 'PASS'), (SELECT count(*) FROM steps WHERE verdict = 'FAIL'),
                       (SELECT count(*) FROM steps WHERE verdict = 'BLOCKED'), (SELECT count(*) FROM steps WHERE verdict = 'INCONCLUSIVE')
                """, p, project, days).query((rs, n) -> new Execution(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rate(rs.getLong(6), rs.getLong(7)))).single();
        List<ProjectQuality> projects = bind(RUNS + """
                SELECT pr.name, (SELECT count(*) FROM runs r WHERE r.project_id = pr.id),
                       (SELECT count(*) FROM steps s WHERE s.project_id = pr.id AND s.verdict = 'PASS'), (SELECT count(*) FROM steps s WHERE s.project_id = pr.id AND s.verdict = 'FAIL'),
                       (SELECT count(*) FROM defect d WHERE d.organization_id = pr.organization_id AND d.project_id = pr.id AND d.status IN ('OPEN','TRIAGED')),
                       (SELECT count(*) FROM defect d WHERE d.organization_id = pr.organization_id AND d.project_id = pr.id AND d.status IN ('OPEN','TRIAGED') AND d.severity = 'CRITICAL'),
                       (SELECT count(*) FROM defect d WHERE d.organization_id = pr.organization_id AND d.project_id = pr.id AND d.status = 'FIXED'),
                       (SELECT count(*) FROM test_case c WHERE c.organization_id = pr.organization_id AND c.project_id = pr.id AND c.archived_at IS NULL)
                FROM project pr WHERE pr.organization_id = :org AND pr.id IN %s ORDER BY pr.name
                """.formatted(visible), p, project, days).query((rs, n) -> new ProjectQuality(rs.getString(1), rs.getLong(2), rate(rs.getLong(3), rs.getLong(4)), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8))).list();
        List<Coverage> coverage = bind("""
                SELECT pr.name, count(c.id), count(c.id) FILTER (WHERE c.source = 'AUTOMATED'), count(c.id) FILTER (WHERE c.source = 'MANUAL'),
                       count(c.id) FILTER (WHERE c.status = 'READY'), count(c.id) FILTER (WHERE c.status = 'DRAFT'), count(c.id) FILTER (WHERE c.status = 'DEPRECATED'),
                       count(c.id) FILTER (WHERE c.suite_id IS NOT NULL),
                       count(c.id) FILTER (WHERE EXISTS (SELECT 1 FROM test_result r JOIN test_case_version v ON v.organization_id = r.organization_id AND v.id = r.test_case_version_id
                                                         WHERE v.organization_id = c.organization_id AND v.test_case_id = c.id)
                                              OR EXISTS (SELECT 1 FROM manual_result m WHERE m.organization_id = c.organization_id AND m.test_case_id = c.id))
                FROM project pr LEFT JOIN test_case c ON c.organization_id = pr.organization_id AND c.project_id = pr.id AND c.archived_at IS NULL
                WHERE pr.organization_id = :org AND pr.id IN %s GROUP BY pr.id, pr.name ORDER BY pr.name
                """.formatted(visible), p, project, days).query((rs, n) -> new Coverage(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9))).list();
        Defects defects = new Defects(counts(p, project, "d.severity"), counts(p, project, "d.status"), counts(p, project, "d.type"));
        List<Channel> channels = bind(RUNS + """
                SELECT c.channel, (SELECT count(*) FROM runs r WHERE r.channel = c.channel),
                       (SELECT count(*) FROM steps s WHERE s.channel = c.channel AND s.verdict = 'PASS'), (SELECT count(*) FROM steps s WHERE s.channel = c.channel AND s.verdict = 'FAIL'),
                       (SELECT count(*) FROM steps s WHERE s.channel = c.channel AND s.verdict = 'BLOCKED'),
                       (SELECT count(DISTINCT f.defect_id) FROM finding f JOIN runs r ON r.organization_id = f.organization_id AND r.id = f.run_id WHERE r.channel = c.channel)
                FROM (VALUES ('WEB'), ('API')) c(channel)
                """, p, project, days).query((rs, n) -> new Channel(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rate(rs.getLong(3), rs.getLong(4)), rs.getLong(6))).list();
        return new Overview(days, execution, projects, coverage, defects, channels, trend(p, project, days));
    }

    @GetMapping("/analytics")
    public Analytics analytics(@AuthenticationPrincipal Principal p, @RequestParam(required = false) UUID projectId, @RequestParam(defaultValue = "30") int days) {
        narrow(p, projectId);
        int d = span(days);
        List<Hotspot> hotspots = bind(RUNS + """
                SELECT coalesce(nullif(s.label, ''), s.url), pr.name, count(*) FILTER (WHERE s.verdict = 'FAIL'), count(*)
                FROM steps s JOIN project pr ON pr.organization_id = :org AND pr.id = s.project_id
                GROUP BY 1, 2 HAVING count(*) FILTER (WHERE s.verdict = 'FAIL') > 0 ORDER BY 3 DESC, 1 LIMIT 10
                """, p, projectId, d).query((rs, n) -> new Hotspot(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4))).list();
        List<SlowOperation> slowest = bind(RUNS + """
                SELECT regexp_replace(s.label, '^\\S+ ', ''), pr.name, count(*), avg(s.duration_ms)::bigint, max(s.duration_ms)
                FROM steps s JOIN project pr ON pr.organization_id = :org AND pr.id = s.project_id
                WHERE s.kind = 'API_CALL' AND s.status > 0 GROUP BY 1, 2 ORDER BY 4 DESC LIMIT 10
                """, p, projectId, d).query((rs, n) -> new SlowOperation(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))).list();
        return new Analytics(d, trend(p, projectId, d), hotspots, slowest, counts(p, projectId, "d.type"));
    }

    private static List<List<String>> table(Overview o, String section) {
        List<List<String>> rows = new ArrayList<>();
        switch (section) {
            case "execution" -> {
                rows.add(List.of("Runs", "Succeeded", "Failed", "Cancelled", "Active", "Steps passed", "Steps failed", "Steps blocked", "Steps inconclusive", "Pass rate %"));
                var e = o.execution();
                rows.add(List.of(s(e.runs()), s(e.succeeded()), s(e.failed()), s(e.cancelled()), s(e.active()), s(e.passed()), s(e.failedSteps()), s(e.blocked()), s(e.inconclusive()), pct(e.passRate())));
            }
            case "projects" -> {
                rows.add(List.of("Project", "Runs", "Pass rate %", "Open defects", "Critical open", "Fixed", "Test cases"));
                o.projects().forEach(q -> rows.add(List.of(q.project(), s(q.runs()), pct(q.passRate()), s(q.openDefects()), s(q.criticalDefects()), s(q.fixedDefects()), s(q.testCases()))));
            }
            case "coverage" -> {
                rows.add(List.of("Project", "Total", "Automated", "Manual", "Ready", "Draft", "Deprecated", "In a suite", "Executed"));
                o.coverage().forEach(c -> rows.add(List.of(c.project(), s(c.total()), s(c.automated()), s(c.manual()), s(c.ready()), s(c.draft()), s(c.deprecated()), s(c.inSuite()), s(c.executed()))));
            }
            case "defects" -> {
                rows.add(List.of("Dimension", "Value", "Count"));
                o.defects().bySeverity().forEach(c -> rows.add(List.of("Severity", c.name(), s(c.count()))));
                o.defects().byStatus().forEach(c -> rows.add(List.of("Status", c.name(), s(c.count()))));
                o.defects().byType().forEach(c -> rows.add(List.of("Type", c.name(), s(c.count()))));
            }
            case "channels" -> {
                rows.add(List.of("Channel", "Runs", "Passed", "Failed", "Blocked", "Pass rate %", "Defects"));
                o.channels().forEach(c -> rows.add(List.of(c.channel(), s(c.runs()), s(c.passed()), s(c.failed()), s(c.blocked()), pct(c.passRate()), s(c.defects()))));
            }
            case "trend" -> {
                rows.add(List.of("Day", "Runs", "Passed", "Failed", "Pass rate %", "Defects opened", "Defects closed"));
                o.trend().forEach(t -> rows.add(List.of(t.day().toString(), s(t.runs()), s(t.passed()), s(t.failed()), pct(t.passRate()), s(t.defectsOpened()), s(t.defectsClosed()))));
            }
            default -> throw new ConflictException("Unknown report section");
        }
        return rows;
    }

    private static final List<String> SECTIONS = List.of("execution", "projects", "coverage", "defects", "channels", "trend");

    private static String s(long v) {
        return Long.toString(v);
    }

    private static String pct(Double v) {
        return v == null ? "" : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String csvCell(String v) {
        return '"' + ReportController.safe(v).replace("\"", "\"\"") + '"';
    }

    private static HttpHeaders attachment(String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(name).build());
        return headers;
    }

    @GetMapping("/reports/export.csv")
    public ResponseEntity<String> csv(@AuthenticationPrincipal Principal p, @RequestParam(required = false) UUID projectId, @RequestParam(defaultValue = "30") int days, @RequestParam String section) {
        narrow(p, projectId);
        List<List<String>> rows = table(build(p, projectId, span(days)), section);
        audit.record(p.organizationId(), projectId, p.email(), "REPORT_EXPORTED", section, "csv");
        String body = rows.stream().map(r -> r.stream().map(InsightsController::csvCell).collect(Collectors.joining(","))).collect(Collectors.joining("\r\n"));
        return ResponseEntity.ok().headers(attachment("qa-" + section + ".csv")).contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8)).body(body);
    }

    @GetMapping("/reports/export.xlsx")
    public ResponseEntity<byte[]> xlsx(@AuthenticationPrincipal Principal p, @RequestParam(required = false) UUID projectId, @RequestParam(defaultValue = "30") int days) throws IOException {
        narrow(p, projectId);
        Overview o = build(p, projectId, span(days));
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            for (String section : SECTIONS) {
                Sheet sheet = book.createSheet(section);
                int r = 0;
                for (List<String> values : table(o, section)) {
                    Row row = sheet.createRow(r++);
                    for (int c = 0; c < values.size(); c++) row.createCell(c).setCellValue(ReportController.safe(values.get(c)));
                }
            }
            book.write(out);
            audit.record(p.organizationId(), projectId, p.email(), "REPORT_EXPORTED", "overview", "xlsx");
            return ResponseEntity.ok().headers(attachment("qa-report.xlsx"))
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(out.toByteArray());
        }
    }
}
