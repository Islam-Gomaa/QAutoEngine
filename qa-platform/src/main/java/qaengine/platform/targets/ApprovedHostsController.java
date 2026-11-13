package qaengine.platform.targets;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.Principal;

/**
 * Which real hosts this deployment approved, and the history of changes, for Organization Admins. Read-only: hosts
 * are approved in the deployment's file by an operator, never from the product.
 */
@RestController
@RequestMapping("/api/v1/approved-hosts")
public class ApprovedHostsController {

    private final ApprovedHostsPolicy policy;
    private final Access access;

    public ApprovedHostsController(ApprovedHostsPolicy policy, Access access) {
        this.policy = policy;
        this.access = access;
    }

    @GetMapping
    public ApprovedHostsPolicy.View list(@AuthenticationPrincipal Principal p) {
        access.requireOrganizationAdmin(p);
        return policy.view();
    }
}
