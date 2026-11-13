package qaengine.platform.shared;

import java.io.Serializable;
import java.util.UUID;

/** The authenticated caller. Built on the server from the session; clients never supply it. */
public record Principal(UUID userId, UUID organizationId, String email, boolean organizationAdmin) implements Serializable {}
