package qaengine.web.bakeoff;

import java.util.List;
import java.util.function.BiPredicate;

/** The minimum a browser driver must do for the safety and evidence layers. Same contract for all candidates. */
interface Probe extends AutoCloseable {
    String name();
    /** Decides per request: true means block. Must be applied before the request is sent. */
    void guard(BiPredicate<String, String> blockMethodUrl);
    void open(String url);
    Object js(String script);
    void click(String cssSelector);
    /** Runs a page script in a new tab, then closes the tab. */
    void inNewTab(String url, String script);
    /** Signals collected since the last call: "console:", "exception:", "status:NNN url". */
    List<String> drainSignals();
    byte[] maskedScreenshot(String cssSelector);
    @Override void close();
}
