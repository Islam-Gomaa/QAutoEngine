package qaengine.platform.reporting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;

/** Reports are queries over stored data: every number here traces to rows, nothing is estimated. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class ReportController {

    public record Summary(long runs, long openDefects, Map<String, Long> defectsBySeverity, Map<String, Long> defectsByStatus,
                          long testCases, Map<String, Long> latestRunVerdicts, UUID latestRun, long pagesCovered, long blockedActions) {}
    record DefectRow(String severity, String type, String title, String url, String status, int occurrences) {}

    private final JdbcClient db;
    private final Access access;
    private final AuditLog audit;

    public ReportController(JdbcClient db, Access access, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.audit = audit;
    }

    private Map<String, Long> grouped(String sql, Object... params) {
        var out = new java.util.TreeMap<String, Long>();
        db.sql(sql).params(params).query(rs -> { out.put(rs.getString(1), rs.getLong(2)); });
        return out;
    }

    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.VIEWER);
        UUID org = p.organizationId();
        long runs = db.sql("SELECT count(*) FROM test_run WHERE organization_id = ? AND project_id = ?").params(org, projectId).query(Long.class).single();
        long open = db.sql("SELECT count(*) FROM defect WHERE organization_id = ? AND project_id = ? AND status IN ('OPEN','TRIAGED')").params(org, projectId).query(Long.class).single();
        long cases = db.sql("SELECT count(*) FROM test_case WHERE organization_id = ? AND project_id = ?").params(org, projectId).query(Long.class).single();
        var severities = grouped("SELECT severity, count(*) FROM defect WHERE organization_id = ? AND project_id = ? AND status IN ('OPEN','TRIAGED') GROUP BY severity", org, projectId);
        var statuses = grouped("SELECT status, count(*) FROM defect WHERE organization_id = ? AND project_id = ? GROUP BY status", org, projectId);
        UUID latest = db.sql("SELECT id FROM test_run WHERE organization_id = ? AND project_id = ? AND status = 'SUCCEEDED' ORDER BY created_at DESC LIMIT 1")
                .params(org, projectId).query(UUID.class).optional().orElse(null);
        if (latest == null) return new Summary(runs, open, severities, statuses, cases, Map.of(), null, 0, 0);
        var verdicts = grouped("SELECT verdict, count(*) FROM run_step WHERE organization_id = ? AND run_id = ? GROUP BY verdict", org, latest);
        long pages = db.sql("SELECT count(DISTINCT state) FROM run_step WHERE organization_id = ? AND run_id = ? AND state <> ''").params(org, latest).query(Long.class).single();
        return new Summary(runs, open, severities, statuses, cases, verdicts, latest, pages, verdicts.getOrDefault("BLOCKED", 0L));
    }

    public record ApiMetric(String operation, long calls, long passed, long failed, long blocked, long averageMs, long p95Ms, long maxMs) {}

    /** Per-operation results and response times for one run, computed from stored steps. */
    @GetMapping("/runs/{runId}/api-metrics")
    public List<ApiMetric> apiMetrics(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId) {
        access.require(p, projectId, Access.Level.VIEWER);
        requireRun(p, projectId, runId);
        return db.sql("""
                SELECT regexp_replace(label, '^\\S+ ', '') AS operation, count(*),
                       count(*) FILTER (WHERE verdict = 'PASS'), count(*) FILTER (WHERE verdict = 'FAIL'), count(*) FILTER (WHERE verdict = 'BLOCKED'),
                       coalesce(avg(duration_ms) FILTER (WHERE status > 0), 0)::bigint,
                       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) FILTER (WHERE status > 0), 0)::bigint,
                       coalesce(max(duration_ms) FILTER (WHERE status > 0), 0)
                FROM run_step
                WHERE organization_id = ? AND run_id = ? AND kind = 'API_CALL'
                GROUP BY 1 ORDER BY 1
                """).params(p.organizationId(), runId).query((rs, n) -> new ApiMetric(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8))).list();
    }

    private void requireRun(Principal p, UUID projectId, UUID runId) {
        boolean exists = db.sql("SELECT count(*) FROM test_run WHERE organization_id = ? AND project_id = ? AND id = ?")
                .params(p.organizationId(), projectId, runId).query(Long.class).single() == 1;
        if (!exists) throw new NotFoundException("Run");
    }

    private List<DefectRow> runDefects(UUID org, UUID runId) {
        return db.sql("""
                SELECT DISTINCT d.severity, d.type, d.title, d.url, d.status, d.occurrences FROM defect d
                JOIN finding f ON f.organization_id = d.organization_id AND f.defect_id = d.id
                WHERE d.organization_id = ? AND f.run_id = ?
                ORDER BY d.severity, d.url
                """).params(org, runId).query((rs, n) -> new DefectRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6))).list();
    }

    /** Printable HTML. Everything from the target is escaped, and the sandbox header stops any script. */
    @GetMapping("/runs/{runId}/report.html")
    public ResponseEntity<String> html(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId) {
        access.require(p, projectId, Access.Level.VIEWER);
        requireRun(p, projectId, runId);
        audit.record(p.organizationId(), projectId, p.email(), "REPORT_EXPORTED", runId.toString(), "html");
        var verdicts = grouped("SELECT verdict, count(*) FROM run_step WHERE organization_id = ? AND run_id = ? GROUP BY verdict", p.organizationId(), runId);
        StringBuilder rows = new StringBuilder();
        for (DefectRow d : runDefects(p.organizationId(), runId)) {
            rows.append("<tr>");
            for (String cell : List.of(d.severity(), d.type(), d.title(), d.url(), d.status(), String.valueOf(d.occurrences()))) {
                rows.append("<td>").append(HtmlUtils.htmlEscape(cell)).append("</td>");
            }
            rows.append("</tr>");
        }
        String page = """
                <!doctype html><html><head><meta charset="utf-8"><title>QA report</title>
                <style>body{font-family:sans-serif;margin:2rem}table{border-collapse:collapse;width:100%%}td,th{border:1px solid #bbb;padding:.4rem;text-align:left}</style>
                </head><body><h1>QA report</h1><p>Run %s</p><h2>Steps by verdict</h2><p>%s</p>
                <h2>Defects</h2><table><tr><th>Severity</th><th>Type</th><th>Title</th><th>URL</th><th>Status</th><th>Occurrences</th></tr>%s</table>
                </body></html>""".formatted(runId, HtmlUtils.htmlEscape(verdicts.toString()), rows);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'")
                .header("X-Content-Type-Options", "nosniff").body(page);
    }

    @GetMapping("/runs/{runId}/report.xlsx")
    public ResponseEntity<byte[]> excel(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId) throws IOException {
        access.require(p, projectId, Access.Level.VIEWER);
        requireRun(p, projectId, runId);
        audit.record(p.organizationId(), projectId, p.email(), "REPORT_EXPORTED", runId.toString(), "xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Defects");
            String[] headers = {"Severity", "Type", "Title", "URL", "Status", "Occurrences"};
            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) head.createCell(i).setCellValue(headers[i]);
            int n = 1;
            for (DefectRow d : runDefects(p.organizationId(), runId)) {
                Row r = sheet.createRow(n++);
                r.createCell(0).setCellValue(safe(d.severity()));
                r.createCell(1).setCellValue(safe(d.type()));
                r.createCell(2).setCellValue(safe(d.title()));
                r.createCell(3).setCellValue(safe(d.url()));
                r.createCell(4).setCellValue(safe(d.status()));
                r.createCell(5).setCellValue(d.occurrences());
            }
            book.write(out);
            HttpHeaders h = new HttpHeaders();
            h.setContentDisposition(ContentDisposition.attachment().filename("qa-report-" + runId + ".xlsx").build());
            h.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            return ResponseEntity.ok().headers(h).body(out.toByteArray());
        }
    }

    /** Target-controlled text must not become a live spreadsheet formula. */
    static String safe(String value) {
        if (value == null || value.isEmpty()) return "";
        char c = value.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r' ? "'" + value : value;
    }
}
