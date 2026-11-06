package qaengine.core.policy;

import java.util.Map;

/** A control the engine wants to use, described by what can be observed about it. */
public record ActionRequest(Kind kind, String url, Map<String, String> attributes) {

    public enum Kind {
        /** Follows an in-scope link. */
        NAVIGATE_LINK,
        /** Tab, accordion, menu, pagination: reveals existing content. */
        DISCLOSURE,
        FORM_FILL, FORM_SUBMIT, BUTTON, DOWNLOAD, UPLOAD, DIALOG_ACCEPT, UNKNOWN,
        /** One API request. The request itself is still decided separately. */
        API_CALL
    }

    public ActionRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
