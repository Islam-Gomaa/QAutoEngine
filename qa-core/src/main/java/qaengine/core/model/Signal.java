package qaengine.core.model;

/**
 * Something observed while a step ran. These are the raw facts verdicts are built from.
 *
 * @param resource the browser's resource type for responses, such as document, image or fetch
 */
public record Signal(Type type, String url, int status, String message, String resource) {

    public enum Type { RESPONSE, CONSOLE_ERROR, PAGE_EXCEPTION, REQUEST_FAILED, BLOCKED_BY_POLICY, DIALOG_DISMISSED }

    public static Signal response(String url, int status, String resource) {
        return new Signal(Type.RESPONSE, url, status, "", resource);
    }

    public static Signal of(Type type, String url, String message) {
        return new Signal(type, url, 0, message, "");
    }

    public boolean isDocument() {
        return "document".equals(resource);
    }
}
