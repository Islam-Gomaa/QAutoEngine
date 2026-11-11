package qaengine.platform.testdesign;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import qaengine.platform.discovery.DiscoveryStore;
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.testcases.TestCaseService;
import qaengine.platform.testdesign.TestDesignAdvisor.Candidate;
import qaengine.platform.testdesign.TestDesignAdvisor.Discovery;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Saves what the advisor suggests as test cases waiting for review. A case already suggested for the same thing,
 * whatever became of it (approved, edited, rejected or archived), is left alone, so running discovery again never
 * undoes a review or brings back a rejected case.
 */
@Component
public class TestDesignService {

    public record Generated(String generator, int suggested, int created, int alreadyKnown) {}

    private final JdbcClient db;
    private final DiscoveryStore discoveries;
    private final TestCaseService cases;
    private final TestDesignAdvisor advisor;
    private final ObjectMapper json;

    public TestDesignService(JdbcClient db, DiscoveryStore discoveries, TestCaseService cases, TestDesignAdvisor advisor, ObjectMapper json) {
        this.db = db;
        this.discoveries = discoveries;
        this.cases = cases;
        this.advisor = advisor;
        this.json = json;
    }

    static String path(String url) {
        try {
            URI u = URI.create(url);
            String p = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            return u.getRawQuery() == null ? p : p + "?" + u.getRawQuery();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asString();
    }

    /** Reads the latest attempt of a finished discovery run into the advisor's terms. */
    Discovery read(UUID org, UUID run) {
        var summary = discoveries.summary(org, run);
        Map<UUID, String> titles = new LinkedHashMap<>();
        summary.pageRows().forEach(r -> titles.put(r.id(), r.title()));
        List<TestDesignAdvisor.Page> pages = new ArrayList<>();
        for (var row : summary.pageRows()) {
            var d = discoveries.page(org, run, row.id());
            List<TestDesignAdvisor.Form> forms = new ArrayList<>();
            for (JsonNode f : d.structure().path("forms")) {
                List<TestDesignAdvisor.Field> fields = new ArrayList<>();
                for (JsonNode x : f.path("fields")) {
                    fields.add(new TestDesignAdvisor.Field(text(x, "label"), text(x, "name"), text(x, "type"), x.path("required").asBoolean(), x.path("labelled").asBoolean()));
                }
                forms.add(new TestDesignAdvisor.Form(text(f, "name"), text(f, "method"), text(f, "action"), fields, text(f, "submitLabel")));
            }
            List<TestDesignAdvisor.Table> tables = new ArrayList<>();
            for (JsonNode t : d.structure().path("tables")) {
                List<String> headers = new ArrayList<>();
                t.path("headers").forEach(h -> headers.add(h.asString()));
                tables.add(new TestDesignAdvisor.Table(text(t, "caption"), headers, t.path("rows").asInt()));
            }
            var actions = d.actions().stream().map(a -> new TestDesignAdvisor.Action(a.kind(), a.name(), a.href(), a.safety())).toList();
            var links = d.linksTo().stream().map(l -> new TestDesignAdvisor.Link(path(l.url()), titles.getOrDefault(l.pageId(), ""), l.via())).toList();
            pages.add(new TestDesignAdvisor.Page(path(d.url()), d.title(), d.headings(), forms, tables, actions, links));
        }
        boolean signedIn = db.sql("SELECT auth_profile_id IS NOT NULL FROM test_run WHERE organization_id = ? AND id = ?").params(org, run).query(Boolean.class).single();
        return new Discovery(signedIn, pages);
    }

    private static String key(String candidateKey) {
        try {
            return "generated:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(candidateKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public Generated generate(UUID org, UUID project, UUID run) {
        record Run(String kind, boolean finished) {}
        Run r = db.sql("SELECT kind, finished_at IS NOT NULL FROM test_run WHERE organization_id = ? AND project_id = ? AND id = ?").params(org, project, run)
                .query((rs, n) -> new Run(rs.getString(1), rs.getBoolean(2))).optional().orElseThrow(() -> new NotFoundException("Run"));
        if (!r.kind().equals("DISCOVERY")) throw new ConflictException("Test cases are suggested from a discovery run");
        if (!r.finished()) throw new ConflictException("Wait for the discovery run to finish");
        Discovery discovery = read(org, run);
        if (discovery.pages().isEmpty()) throw new ConflictException("The run recorded no pages");
        int attempt = discoveries.summary(org, run).attempt();
        List<Candidate> candidates = advisor.suggest(discovery);
        int created = 0;
        for (Candidate c : candidates) {
            String naturalKey = key(c.key());
            boolean known = db.sql("SELECT count(*) FROM test_case WHERE organization_id = ? AND project_id = ? AND natural_key = ?").params(org, project, naturalKey)
                    .query(Long.class).single() > 0;
            if (known) continue;
            var steps = c.steps().stream().map(s -> Map.of("action", s.action(), "expected", s.expected(), "basis", s.basis().name())).toList();
            var saved = cases.save(org, project, naturalKey, c.title(), json.writeValueAsString(steps));
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("generator", advisor.name());
            provenance.put("rule", c.rule());
            provenance.put("runId", run.toString());
            provenance.put("attempt", attempt);
            provenance.put("observed", c.observed());
            provenance.put("inferred", c.inferred());
            provenance.put("unknown", c.unknown());
            db.sql("""
                    UPDATE test_case SET source = 'GENERATED', status = 'PENDING_REVIEW', description = ?, priority = ?, tags = ?, module = ?, provenance = ?::jsonb, generated_from_run = ?
                    WHERE organization_id = ? AND id = ?
                    """).params(c.description(), c.priority(), c.tags().toArray(String[]::new), c.module(), json.writeValueAsString(provenance), run, org, saved.testCaseId()).update();
            created++;
        }
        return new Generated(advisor.name(), candidates.size(), created, candidates.size() - created);
    }
}
