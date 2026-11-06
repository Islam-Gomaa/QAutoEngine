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
    /** Fills and submits the login form. The caller must have opened the gate's login window. */
    Navigation submitLogin(String username, String password, String oneTimeCode);
    /** Restores a session a person created, as cookies. */
    void importCookies(String name, String value, String url);
    byte[] screenshot(List<String> maskSelectors);
    String domSnapshot();
    @Override void close();
}
