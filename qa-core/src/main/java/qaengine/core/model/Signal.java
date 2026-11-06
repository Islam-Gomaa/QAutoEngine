package qaengine.core.model;

/** Something observed while a step ran. These are the raw facts verdicts are built from. */
public record Signal(Type type, String url, int status, String message, boolean document) {

    public enum Type { RESPONSE, CONSOLE_ERROR, PAGE_EXCEPTION, REQUEST_FAILED, BLOCKED_BY_POLICY, DIALOG_DISMISSED }

    public static Signal response(String url, int status, boolean document) {
        return new Signal(Type.RESPONSE, url, status, "", document);
    }

    public static Signal of(Type type, String url, String message) {
        return new Signal(type, url, 0, message, false);
    }
}
