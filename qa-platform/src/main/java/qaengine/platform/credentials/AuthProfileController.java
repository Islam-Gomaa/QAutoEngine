package qaengine.platform.credentials;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qaengine.platform.identity.Access;
import qaengine.platform.shared.Principal;

/** Sign-in profiles. Responses carry masks and settings, never a password, token or mailbox secret. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class AuthProfileController {

    private final Access access;
    private final AuthProfiles profiles;

    public AuthProfileController(Access access, AuthProfiles profiles) {
        this.access = access;
        this.profiles = profiles;
    }

    @GetMapping("/auth-profiles")
    public List<AuthProfiles.View> list(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        return profiles.list(p.organizationId(), projectId);
    }

    @PostMapping("/environments/{environmentId}/auth-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AuthProfiles.View create(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID environmentId, @RequestBody AuthProfiles.Settings body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        return profiles.get(p.organizationId(), projectId, profiles.create(p, projectId, environmentId, body));
    }

    @PutMapping("/auth-profiles/{id}")
    @Transactional
    public AuthProfiles.View update(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID id, @RequestBody AuthProfiles.Settings body) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        profiles.update(p, projectId, id, body);
        return profiles.get(p.organizationId(), projectId, id);
    }

    @PostMapping("/auth-profiles/{id}/{change:enable|disable}")
    @Transactional
    public AuthProfiles.View setEnabled(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID id, @PathVariable String change) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        profiles.setEnabled(p, projectId, id, change.equals("enable"));
        return profiles.get(p.organizationId(), projectId, id);
    }

    @DeleteMapping("/auth-profiles/{id}")
    @Transactional
    public void delete(@AuthenticationPrincipal Principal p, @PathVariable UUID projectId, @PathVariable UUID id) {
        access.require(p, projectId, Access.Level.PROJECT_ADMIN);
        profiles.delete(p, projectId, id);
    }
}
