package qaengine.core.model;

/**
 * Result of moving the browser. A redirect is reported, never followed: the target goes back
 * through the gate as a new candidate.
 */
public record Navigation(Outcome outcome, int status, String url, String redirectTarget, String detail) {

    public enum Outcome { LOADED, REDIRECTED, BLOCKED, FAILED }

    public static Navigation loaded(int status, String url) { return new Navigation(Outcome.LOADED, status, url, null, ""); }
    public static Navigation redirected(String url, String target) { return new Navigation(Outcome.REDIRECTED, 0, url, target, ""); }
    public static Navigation blocked(String url, String detail) { return new Navigation(Outcome.BLOCKED, 0, url, null, detail); }
    public static Navigation failed(String url, String detail) { return new Navigation(Outcome.FAILED, 0, url, null, detail); }
}
