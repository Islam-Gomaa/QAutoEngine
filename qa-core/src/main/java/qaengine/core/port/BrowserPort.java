package qaengine.core.port;

import java.util.List;
import qaengine.core.model.Control;
import qaengine.core.model.Navigation;
import qaengine.core.model.PageSnapshot;
import qaengine.core.model.Signal;

/** What the engine needs from a browser. Adapters must enforce the gate on every outgoing request. */
public interface BrowserPort extends AutoCloseable {
    Navigation open(String url);
    PageSnapshot snapshot();
    /** Uses a disclosure control in place. The caller has already passed it through the gate. */
    void activate(Control control);
    List<Signal> drainSignals();
    byte[] screenshot(List<String> maskSelectors);
    String domSnapshot();
    @Override void close();
}
