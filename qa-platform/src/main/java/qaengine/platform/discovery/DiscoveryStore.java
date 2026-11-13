package qaengine.platform.discovery;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import qaengine.contracts.WorkerContracts.DiscoveredEdge;
import qaengine.contracts.WorkerContracts.DiscoveredPage;
import qaengine.platform.shared.NotFoundException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps what discovery found, and reads it back. What a worker sends came from pages it does not trust,
 * so every value outside a known set is refused and every text is capped before it is kept.
 */
@Component
public class DiscoveryStore {

    static final Set<String> KINDS = Set.of("LINK", "BUTTON", "SUBMIT", "DISCLOSURE");
    static final Set<String> SAFETY = Set.of("READ_ONLY", "REVERSIBLE", "SENSITIVE", "DESTRUCTIVE");
    private static final Set<String> STRATEGIES = Set.of("ROLE", "LABEL", "PLACEHOLDER", "TEST_ID", "CSS");

    public record PageRow(UUID id, String url, String title, int depth, int forms, int fields, int requiredFields, int unlabelledFields, int tables, int dialogs,
                          Map<String, Long> actions) {}
    /** @param attempt the run attempt this describes: the latest that discovered anything */
    public record Summary(int attempt, int pages, int forms, int fields, int requiredFields, int unlabelledFields, int tables, int dialogs, Map<String, Long> actions,
                          long links, List<PageRow> pageRows) {}
    public record ActionView(String kind, String role, String name, String href, JsonNode locator, String safety, String reason) {}
    public record LinkView(UUID pageId, String url, String via) {}
    public record PageDetail(UUID id, String url, String title, int depth, List<String> headings, JsonNode structure, List<ActionView> actions,
                             List<LinkView> linksTo, UUID screenshot, OffsetDateTime at) {}

    private final JdbcClient db;
    private final ObjectMapper json;

    public DiscoveryStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Refuses anything a correct worker would never send. Returns false when the page was already recorded. */
    public boolean page(UUID org, UUID run, int attempt, DiscoveredPage p) {
        if (p.fingerprint() == null || !p.fingerprint().matches("[0-9a-f]{1,64}") || p.depth() < 0) throw new IllegalArgumentException("bad page");
        var actions = p.actions() == null ? List.<qaengine.contracts.WorkerContracts.ActionReport>of() : p.actions();
        if (actions.size() > 200) throw new IllegalArgumentException("too many actions");
        for (var a : actions) {
            if (!KINDS.contains(a.kind()) || !SAFETY.contains(a.safety())) throw new IllegalArgumentException("unknown action kind or safety");
            if (a.locator() != null && !STRATEGIES.contains(a.locator().strategy())) throw new IllegalArgumentException("unknown locator strategy");
        }
        var forms = p.forms() == null ? List.<qaengine.contracts.WorkerContracts.FormReport>of() : p.forms();
        int fields = 0, required = 0, unlabelled = 0;
        for (var f : forms) {
            for (var x : f.fields() == null ? List.<qaengine.contracts.WorkerContracts.FieldReport>of() : f.fields()) {
                fields++;
                if (x.required()) required++;
                if (!x.labelled()) unlabelled++;
            }
        }
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("navigation", p.navigation() == null ? List.of() : p.navigation());
        structure.put("forms", forms);
        structure.put("tables", p.tables() == null ? List.of() : p.tables());
        structure.put("dialogs", p.dialogs() == null ? List.of() : p.dialogs());
        String text = json.writeValueAsString(structure);
        if (text.length() > 500_000) throw new IllegalArgumentException("page too large");
        UUID id = UUID.randomUUID();
        List<String> headings = (p.headings() == null ? List.<String>of() : p.headings()).stream().limit(30).map(h -> cap(h, 200)).toList();
        int inserted = db.sql("""
                INSERT INTO discovered_page (organization_id, id, run_id, attempt, fingerprint, step, depth, url, title, headings, structure, forms, fields, required_fields,
                                             unlabelled_fields, tables, dialogs)
                VALUES (?,?,?,?,?,?,?,?,?,?, ?::jsonb, ?,?,?,?,?,?)
                ON CONFLICT (organization_id, run_id, attempt, fingerprint) DO NOTHING
                """).params(org, id, run, attempt, p.fingerprint(), p.step(), p.depth(), cap(p.url(), 2000), cap(p.title(), 200), headings.toArray(String[]::new), text,
                forms.size(), fields, required, unlabelled, p.tables() == null ? 0 : p.tables().size(), p.dialogs() == null ? 0 : p.dialogs().size()).update();
        if (inserted == 0) return false;
        int ordinal = 0;
        for (var a : actions) {
            db.sql("""
                    INSERT INTO discovered_action (organization_id, page_id, ordinal, kind, role, name, href, locator, safety, reason)
                    VALUES (?,?,?,?,?,?,?, ?::jsonb, ?,?)
                    """).params(org, id, ordinal++, a.kind(), cap(a.role(), 40), cap(a.name(), 200), a.href() == null ? null : cap(a.href(), 2000),
                    a.locator() == null ? null : json.writeValueAsString(a.locator()), a.safety(), cap(a.reason(), 200)).update();
        }
        return true;
    }

    public void edges(UUID org, UUID run, int attempt, List<DiscoveredEdge> edges) {
        if (edges.size() > 500) throw new IllegalArgumentException("too many edges");
        for (DiscoveredEdge e : edges) {
            if (e.from() == null || e.to() == null || !e.from().matches("[0-9a-f]{1,64}") || !e.to().matches("[0-9a-f]{1,64}")) throw new IllegalArgumentException("bad edge");
            db.sql("""
                    INSERT INTO discovered_edge (organization_id, run_id, attempt, from_fingerprint, to_fingerprint, via) VALUES (?,?,?,?,?,?)
                    ON CONFLICT DO NOTHING
                    """).params(org, run, attempt, e.from(), e.to(), cap(e.via(), 200)).update();
        }
    }

    private Integer latestAttempt(UUID org, UUID run) {
        return db.sql("SELECT max(attempt) FROM discovered_page WHERE organization_id = ? AND run_id = ?").params(org, run).query(Integer.class).optional().orElse(null);
    }

    private static Map<String, Long> bySafety(String[] safeties) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String s : List.of("READ_ONLY", "REVERSIBLE", "SENSITIVE", "DESTRUCTIVE")) out.put(s, 0L);
        if (safeties != null) for (String s : safeties) out.merge(s, 1L, Long::sum);
        return out;
    }

    public Summary summary(UUID org, UUID run) {
        Integer attempt = latestAttempt(org, run);
        if (attempt == null) return new Summary(0, 0, 0, 0, 0, 0, 0, 0, bySafety(null), 0, List.of());
        List<PageRow> rows = db.sql("""
                SELECT p.id, p.url, p.title, p.depth, p.forms, p.fields, p.required_fields, p.unlabelled_fields, p.tables, p.dialogs,
                       ARRAY(SELECT a.safety FROM discovered_action a WHERE a.organization_id = p.organization_id AND a.page_id = p.id)
                FROM discovered_page p WHERE p.organization_id = ? AND p.run_id = ? AND p.attempt = ? ORDER BY p.depth, p.url
                """).params(org, run, attempt).query((rs, n) -> new PageRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), bySafety((String[]) rs.getArray(11).getArray()))).list();
        Map<String, Long> actions = bySafety(null);
        int forms = 0, fields = 0, required = 0, unlabelled = 0, tables = 0, dialogs = 0;
        for (PageRow r : rows) {
            r.actions().forEach((k, v) -> actions.merge(k, v, Long::sum));
            forms += r.forms(); fields += r.fields(); required += r.requiredFields(); unlabelled += r.unlabelledFields(); tables += r.tables(); dialogs += r.dialogs();
        }
        long links = db.sql("SELECT count(*) FROM discovered_edge WHERE organization_id = ? AND run_id = ? AND attempt = ?").params(org, run, attempt).query(Long.class).single();
        return new Summary(attempt, rows.size(), forms, fields, required, unlabelled, tables, dialogs, actions, links, rows);
    }

    public PageDetail page(UUID org, UUID run, UUID pageId) {
        record Row(UUID id, int attempt, String fingerprint, int step, String url, String title, int depth, String[] headings, String structure, OffsetDateTime at) {}
        Row p = db.sql("""
                SELECT id, attempt, fingerprint, step, url, title, depth, headings, structure::text, created_at FROM discovered_page
                WHERE organization_id = ? AND run_id = ? AND id = ?
                """).params(org, run, pageId).query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6),
                rs.getInt(7), (String[]) rs.getArray(8).getArray(), rs.getString(9), rs.getObject(10, OffsetDateTime.class))).optional().orElseThrow(() -> new NotFoundException("Page"));
        List<ActionView> actions = db.sql("""
                SELECT kind, role, name, href, locator::text, safety, reason FROM discovered_action WHERE organization_id = ? AND page_id = ? ORDER BY ordinal
                """).params(org, pageId).query((rs, n) -> new ActionView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5) == null ? null : json.readTree(rs.getString(5)), rs.getString(6), rs.getString(7))).list();
        List<LinkView> links = db.sql("""
                SELECT t.id, t.url, e.via FROM discovered_edge e
                JOIN discovered_page t ON t.organization_id = e.organization_id AND t.run_id = e.run_id AND t.attempt = e.attempt AND t.fingerprint = e.to_fingerprint
                WHERE e.organization_id = ? AND e.run_id = ? AND e.attempt = ? AND e.from_fingerprint = ? ORDER BY t.url
                """).params(org, run, p.attempt(), p.fingerprint()).query((rs, n) -> new LinkView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
        UUID screenshot = db.sql("""
                SELECT id FROM evidence WHERE organization_id = ? AND run_id = ? AND attempt = ? AND step = ? AND type = 'SCREENSHOT' AND deleted_at IS NULL ORDER BY created_at LIMIT 1
                """).params(org, run, p.attempt(), p.step()).query(UUID.class).optional().orElse(null);
        return new PageDetail(p.id(), p.url(), p.title(), p.depth(), Arrays.asList(p.headings()), json.readTree(p.structure()), actions, links, screenshot, p.at());
    }
}
