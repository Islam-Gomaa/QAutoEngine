package qaengine.core.model;

import java.util.List;

/** One observation of a page, taken in a single pass. */
public record PageSnapshot(String url, String title, List<Control> controls, String fingerprint) {
    public PageSnapshot {
        controls = List.copyOf(controls);
    }
}
