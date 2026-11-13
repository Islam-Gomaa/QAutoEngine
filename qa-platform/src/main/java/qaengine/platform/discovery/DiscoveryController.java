package qaengine.platform.discovery;

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

/** What a discovery run found. Readable by anyone who can see the project's runs. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs/{runId}/discovery")
public class DiscoveryController {

    private final JdbcClient db;
    private final Access access;
    private final DiscoveryStore store;

    public DiscoveryController(JdbcClient db, Access access, DiscoveryStore store) {
        this.db = db;
        this.access = access;
        this.store = store;
    }

    private void requireRun(Principal p, UUID projectId, UUID runId) {
        access.require(p, projectId, Access.Level.VIEWER);
        boolean found = db.sql("SELECT count(*) FROM test_run WHERE organization_id = ? AND project_id = ? AND id = ?").params(p.organizationId(), projectId, runId)
                .query(Long.class).single() == 1;
        if (!found) throw new NotFoundException("Run");
    }

    @GetMapping
    public DiscoveryStore.Summary summary(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId) {
        requireRun(p, projectId, runId);
        return store.summary(p.organizationId(), runId);
    }

    @GetMapping("/pages/{pageId}")
    public DiscoveryStore.PageDetail page(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId, @PathVariable UUID pageId) {
        requireRun(p, projectId, runId);
        return store.page(p.organizationId(), runId, pageId);
    }
}
