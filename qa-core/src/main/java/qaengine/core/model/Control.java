package qaengine.core.model;

import java.util.List;
import java.util.Map;
import qaengine.core.policy.ActionRequest;

/**
 * An interactive element. Locators are ordered from most to least stable, and are recorded so a
 * step can be replayed against the exact element later.
 */
public record Control(ActionRequest.Kind kind, String url, String label, Map<String, String> attributes, List<String> locators) {

    public Control {
        attributes = Map.copyOf(attributes);
        locators = List.copyOf(locators);
    }

    public ActionRequest toAction() {
        return new ActionRequest(kind, url, attributes);
    }
}
