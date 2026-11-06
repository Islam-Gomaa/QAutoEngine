package qaengine.platform.overview;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.Principal;

/**
 * Cross-project views. Every query is limited to {@link Access#VISIBLE_PROJECTS}, so a member sees
 * totals for their own projects only, and a project filter can only narrow that set.
 * Pass rate is PASS / (PASS + FAIL): blocked steps were refused by the safety gate and say nothing about quality.
 */
@RestController
@RequestMapping("/api/v1")
public class OverviewController {

    public record Stats(long projects, long applications, long testCases, long runs, Double passRate, long failed, long blocked, long openDefects) {}
    public record Day(LocalDate day, long runs, long passed, long failed, long blocked, long inconclusive) {}
    public record Distribution(long passed, long failed, long blocked, long inconclusive) {}
    public record RecentRun(UUID id, UUID projectId, String projectName, String applicationName, String environmentName, String channel, String status, OffsetDateTime createdAt, long passed, long failed, long blocked) {}
    public record RecentDefect(UUID id, UUID projectId, String projectName, long number, String title, String severity, String status, OffsetDateTime createdAt) {}
    public record ProjectHealth(UUID id, String name, long runs, Double passRate, long openDefects, long criticalDefects, String lastRunStatus, OffsetDateTime lastRunAt) {}
    public record ChannelRow(String channel, long runs, long passed, long failed, long blocked, Double passRate) {}
    public record Activity(OffsetDateTime at, String actor, String action, String object, String detail, UUID projectId, String projectName) {}
    public record Dashboard(Stats stats, List<Day> trend, Distribution distribution, List<RecentRun> recentRuns, List<RecentDefect> recentDefects,
                            List<ProjectHealth> projects, List<ChannelRow> channels, List<Activity> activity) {}
    public record SearchHit(String kind, UUID id, UUID projectId, String title, String subtitle) {}
    public record Notification(String kind, UUID id, UUID projectId, String title, String detail, OffsetDateTime at, boolean unread) {}
    public record Notifications(List<Notification> items, long unread) {}

    /** The steps of each run's last attempt, so a retried run is not counted twice. */
    private static final String STEPS = """
            WITH runs AS (
                SELECT r.organization_id, r.id, r.project_id, r.created_at, e.channel,
                       (SELECT max(x.attempt) FROM run_step x WHERE x.organization_id = r.organization_id AND x.run_id = r.id) AS attempt
                FROM test_run r JOIN environment e ON e.organization_id = r.organization_id AND e.id = r.environment_id
                WHERE r.organization_id = :org AND r.project_id IN %s
            ), steps AS (
                SELECT runs.id AS run_id, runs.project_id, runs.created_at, runs.channel, s.verdict
                FROM runs LEFT JOIN run_step s ON s.organization_id = runs.organization_id AND s.run_id = runs.id AND s.attempt = runs.attempt
            )
            """.formatted(Access.VISIBLE_PROJECTS);

    private static final String ACTIVITY_ACTIONS = "a.action NOT IN ('LOGIN','LOGOUT','EVIDENCE_READ','CREDENTIAL_RELEASED','LOGIN_FAILED_TOTP','JOB_LEASE_EXPIRED')";

    private final JdbcClient db;
    private final Access access;

    public OverviewController(JdbcClient db, Access access) {
        this.db = db;
        this.access = access;
    }

    private JdbcClient.StatementSpec bind(String sql, Principal p, UUID project) {
        return db.sql(sql).param("org", p.organizationId()).param("admin", p.organizationAdmin()).param("user", p.userId()).param("project", project);
    }

    private static Double rate(long passed, long failed) {
        return passed + failed == 0 ? null : passed * 100.0 / (passed + failed);
    }

    private void narrow(Principal p, UUID project) {
        if (project != null) access.require(p, project, Access.Level.VIEWER);
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard(@AuthenticationPrincipal Principal p, @RequestParam(required = false) UUID projectId, @RequestParam(defaultValue = "14") int days) {
        narrow(p, projectId);
        int span = Math.min(Math.max(days, 1), 90);
        String visible = Access.VISIBLE_PROJECTS;
        Distribution d = bind(STEPS + """
                SELECT count(*) FILTER (WHERE verdict = 'PASS'), count(*) FILTER (WHERE verdict = 'FAIL'),
                       count(*) FILTER (WHERE verdict = 'BLOCKED'), count(*) FILTER (WHERE verdict = 'INCONCLUSIVE') FROM steps
                """, p, projectId).query((rs, n) -> new Distribution(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4))).single();
        Stats stats = bind("""
                SELECT (SELECT count(*) FROM %1$s v),
                       (SELECT count(*) FROM target t WHERE t.organization_id = :org AND t.archived_at IS NULL AND t.project_id IN %1$s),
                       (SELECT count(*) FROM test_case c WHERE c.organization_id = :org AND c.archived_at IS NULL AND c.project_id IN %1$s),
                       (SELECT count(*) FROM test_run r WHERE r.organization_id = :org AND r.project_id IN %1$s),
                       (SELECT count(*) FROM defect x WHERE x.organization_id = :org AND x.status IN ('OPEN','TRIAGED') AND x.project_id IN %1$s)
                """.formatted(visible), p, projectId)
                .query((rs, n) -> new Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rate(d.passed(), d.failed()), d.failed(), d.blocked(), rs.getLong(5))).single();
        List<Day> trend = bind(STEPS + """
                SELECT g.day::date, count(DISTINCT s.run_id), count(*) FILTER (WHERE s.verdict = 'PASS'), count(*) FILTER (WHERE s.verdict = 'FAIL'),
                       count(*) FILTER (WHERE s.verdict = 'BLOCKED'), count(*) FILTER (WHERE s.verdict = 'INCONCLUSIVE')
                FROM generate_series(current_date - (:span - 1), current_date, interval '1 day') g(day)
                LEFT JOIN steps s ON s.created_at::date = g.day::date
                GROUP BY g.day ORDER BY g.day
                """, p, projectId).param("span", span)
                .query((rs, n) -> new Day(rs.getObject(1, LocalDate.class), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6))).list();
        List<RecentRun> runs = bind(STEPS + """
                SELECT r.id, r.project_id, pr.name, t.name, e.name, e.channel, r.status, r.created_at,
                       (SELECT count(*) FROM steps s WHERE s.run_id = r.id AND s.verdict = 'PASS'),
                       (SELECT count(*) FROM steps s WHERE s.run_id = r.id AND s.verdict = 'FAIL'),
                       (SELECT count(*) FROM steps s WHERE s.run_id = r.id AND s.verdict = 'BLOCKED')
                FROM test_run r
                JOIN project pr ON pr.organization_id = r.organization_id AND pr.id = r.project_id
                JOIN environment e ON e.organization_id = r.organization_id AND e.id = r.environment_id
                JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                WHERE r.organization_id = :org AND r.id IN (SELECT id FROM runs) ORDER BY r.created_at DESC LIMIT 8
                """, p, projectId).query((rs, n) -> new RecentRun(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getObject(8, OffsetDateTime.class), rs.getLong(9), rs.getLong(10), rs.getLong(11))).list();
        List<RecentDefect> defects = bind("""
                SELECT d.id, d.project_id, pr.name, d.number, d.title, d.severity, d.status, d.created_at FROM defect d
                JOIN project pr ON pr.organization_id = d.organization_id AND pr.id = d.project_id
                WHERE d.organization_id = :org AND d.project_id IN %s ORDER BY d.number DESC LIMIT 8
                """.formatted(visible), p, projectId).query((rs, n) -> new RecentDefect(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class))).list();
        return new Dashboard(stats, trend, d, runs, defects, health(p, projectId), channels(p, projectId), activity(p, projectId, 12));
    }

    private List<ProjectHealth> health(Principal p, UUID projectId) {
        return bind(STEPS + """
                SELECT pr.id, pr.name, (SELECT count(*) FROM runs r WHERE r.project_id = pr.id),
                       (SELECT count(*) FROM steps s WHERE s.project_id = pr.id AND s.verdict = 'PASS'),
                       (SELECT count(*) FROM steps s WHERE s.project_id = pr.id AND s.verdict = 'FAIL'),
                       (SELECT count(*) FROM defect d WHERE d.organization_id = pr.organization_id AND d.project_id = pr.id AND d.status IN ('OPEN','TRIAGED')),
                       (SELECT count(*) FROM defect d WHERE d.organization_id = pr.organization_id AND d.project_id = pr.id AND d.status IN ('OPEN','TRIAGED') AND d.severity = 'CRITICAL'),
                       lr.status, lr.created_at
                FROM project pr
                LEFT JOIN LATERAL (SELECT r.status, r.created_at FROM test_run r WHERE r.organization_id = pr.organization_id AND r.project_id = pr.id ORDER BY r.created_at DESC LIMIT 1) lr ON true
                WHERE pr.organization_id = :org AND pr.id IN %s ORDER BY pr.name
                """.formatted(Access.VISIBLE_PROJECTS), p, projectId).query((rs, n) -> new ProjectHealth(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rate(rs.getLong(4), rs.getLong(5)),
                rs.getLong(6), rs.getLong(7), rs.getString(8), rs.getObject(9, OffsetDateTime.class))).list();
    }

    private List<ChannelRow> channels(Principal p, UUID projectId) {
        return bind(STEPS + """
                SELECT channel, count(DISTINCT run_id), count(*) FILTER (WHERE verdict = 'PASS'), count(*) FILTER (WHERE verdict = 'FAIL'), count(*) FILTER (WHERE verdict = 'BLOCKED')
                FROM steps GROUP BY channel ORDER BY channel DESC
                """, p, projectId).query((rs, n) -> new ChannelRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rate(rs.getLong(3), rs.getLong(4)))).list();
    }

    /** Organization-level events have no project, so only administrators see them. */
    private List<Activity> activity(Principal p, UUID projectId, int limit) {
        return bind("""
                SELECT a.at, a.actor, a.action, a.object, a.detail, a.project_id, pr.name FROM audit_event a
                LEFT JOIN project pr ON pr.organization_id = a.organization_id AND pr.id = a.project_id
                WHERE a.organization_id = :org AND %s
                  AND (a.project_id IN %s OR (:admin AND a.project_id IS NULL AND cast(:project as uuid) IS NULL))
                ORDER BY a.at DESC, a.id DESC LIMIT :limit
                """.formatted(ACTIVITY_ACTIONS, Access.VISIBLE_PROJECTS), p, projectId).param("limit", limit)
                .query((rs, n) -> new Activity(rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7))).list();
    }

    @GetMapping("/activity")
    public List<Activity> activityFeed(@AuthenticationPrincipal Principal p, @RequestParam(required = false) UUID projectId, @RequestParam(defaultValue = "50") int size) {
        narrow(p, projectId);
        return activity(p, projectId, Math.min(Math.max(size, 1), 200));
    }

    @GetMapping("/search")
    public List<SearchHit> search(@AuthenticationPrincipal Principal p, @RequestParam String q) {
        String term = q.trim();
        if (term.length() < 2) return List.of();
        String number = term.replaceFirst("(?i)^DEF-", "");
        String visible = Access.VISIBLE_PROJECTS;
        return bind("""
                (SELECT 'PROJECT', pr.id, pr.id, pr.name, pr.description FROM project pr
                 WHERE pr.organization_id = :org AND pr.id IN %1$s AND pr.name ILIKE '%%' || :q || '%%' ORDER BY pr.name LIMIT 5)
                UNION ALL
                (SELECT 'SUITE', s.id, s.project_id, s.name, s.module FROM test_suite s
                 WHERE s.organization_id = :org AND s.project_id IN %1$s AND s.archived_at IS NULL AND s.name ILIKE '%%' || :q || '%%' ORDER BY s.name LIMIT 5)
                UNION ALL
                (SELECT 'TEST_CASE', c.id, c.project_id, c.title, c.module FROM test_case c
                 WHERE c.organization_id = :org AND c.project_id IN %1$s AND c.archived_at IS NULL AND c.title ILIKE '%%' || :q || '%%' ORDER BY c.title LIMIT 5)
                UNION ALL
                (SELECT 'DEFECT', d.id, d.project_id, 'DEF-' || d.number || ' ' || d.title, d.severity FROM defect d
                 WHERE d.organization_id = :org AND d.project_id IN %1$s AND (d.title ILIKE '%%' || :q || '%%' OR d.number::text = :number) ORDER BY d.number DESC LIMIT 5)
                """.formatted(visible), p, null).param("q", term).param("number", number)
                .query((rs, n) -> new SearchHit(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5))).list();
    }

    @GetMapping("/notifications")
    public Notifications notifications(@AuthenticationPrincipal Principal p) {
        var seen = db.sql("SELECT coalesce(notifications_seen_at, 'epoch'::timestamptz) FROM app_user WHERE id = ?").param(p.userId()).query(OffsetDateTime.class).single();
        var items = bind("""
                SELECT * FROM (
                    (SELECT 'RUN', r.id, r.project_id, r.status, t.name || ' / ' || e.name, r.finished_at AS at FROM test_run r
                     JOIN environment e ON e.organization_id = r.organization_id AND e.id = r.environment_id
                     JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                     WHERE r.organization_id = :org AND r.project_id IN %1$s AND r.finished_at IS NOT NULL ORDER BY r.finished_at DESC LIMIT 15)
                    UNION ALL
                    (SELECT 'DEFECT', d.id, d.project_id, d.severity, 'DEF-' || d.number || ' ' || d.title, d.created_at FROM defect d
                     WHERE d.organization_id = :org AND d.project_id IN %1$s AND d.severity IN ('CRITICAL','HIGH') ORDER BY d.created_at DESC LIMIT 15)
                ) x ORDER BY at DESC LIMIT 20
                """.formatted(Access.VISIBLE_PROJECTS), p, null)
                .query((rs, n) -> {
                    OffsetDateTime at = rs.getObject(6, OffsetDateTime.class);
                    return new Notification(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), at, at.isAfter(seen));
                }).list();
        return new Notifications(items, items.stream().filter(Notification::unread).count());
    }

    @PostMapping("/notifications/seen")
    @Transactional
    public void seen(@AuthenticationPrincipal Principal p) {
        db.sql("UPDATE app_user SET notifications_seen_at = now() WHERE id = ?").param(p.userId()).update();
    }
}
