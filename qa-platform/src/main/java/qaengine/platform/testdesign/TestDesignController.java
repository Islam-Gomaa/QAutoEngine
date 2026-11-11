package qaengine.platform.testdesign;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.Principal;

/** Suggests test cases from a finished discovery run. They wait for review; nothing is executed. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs/{runId}/discovery/test-cases")
public class TestDesignController {

    private final Access access;
    private final TestDesignService service;
    private final AuditLog audit;

    public TestDesignController(Access access, TestDesignService service, AuditLog audit) {
        this.access = access;
        this.service = service;
        this.audit = audit;
    }

    @PostMapping
    @org.springframework.transaction.annotation.Transactional
    public TestDesignService.Generated generate(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID runId) {
        access.require(p, projectId, Access.Level.TESTER);
        var result = service.generate(p.organizationId(), projectId, runId);
        audit.record(p.organizationId(), projectId, p.email(), "TEST_CASES_GENERATED", runId.toString(),
                result.created() + " new, " + result.alreadyKnown() + " already known, by " + result.generator());
        return result;
    }
}
