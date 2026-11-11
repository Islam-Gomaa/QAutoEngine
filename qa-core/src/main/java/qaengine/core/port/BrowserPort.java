package qaengine.core.port;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import qaengine.core.auth.LocatorSpec;
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
    /** Fills and submits the first login form on the page. The caller must have opened the gate's login window. */
    Navigation submitLogin(String username, String password, String oneTimeCode);
    /** Restores a session a person created, as cookies. */
    void importCookies(String name, String value, String url);
    byte[] screenshot(List<String> maskSelectors);
    String domSnapshot();
    @Override void close();

    // Configured login forms. Defaults keep simple test browsers working; the Playwright adapter implements them all.

    /** The URL the current page is showing. */
    default String currentUrl() {
        throw new UnsupportedOperationException("currentUrl");
    }

    /** Whether the element is on the page and visible now. */
    default boolean isVisible(LocatorSpec element) {
        throw new UnsupportedOperationException("isVisible");
    }

    /** Waits up to the timeout for the element to become visible. */
    default boolean appears(LocatorSpec element, Duration timeout) {
        return isVisible(element);
    }

    /** Types into a field. The caller must have opened the gate's login window. Never logs the value. */
    default void fill(LocatorSpec field, String value) {
        throw new UnsupportedOperationException("fill");
    }

    /**
     * Types a one-time code: into the one field, or one character per box when the locator finds several.
     * Returns false, typing nothing, when the boxes do not match the code's length.
     */
    default boolean fillCode(LocatorSpec field, String code) {
        fill(field, code);
        return true;
    }

    /** Clicks the button and waits for a navigation, or for the page to settle if it stays where it is. */
    default Navigation submitWith(LocatorSpec button, Duration timeout) {
        throw new UnsupportedOperationException("submitWith");
    }

    /**
     * Reads the login form without touching it and suggests locators for "username", "password" and
     * "submit", preferring test ids, labels and roles over CSS. Empty when there is no login form.
     */
    default Map<String, LocatorSpec> suggestLoginFields() {
        return Map.of();
    }

    /** A screenshot with these elements masked as well as the selectors. */
    default byte[] screenshot(List<String> maskSelectors, List<LocatorSpec> maskElements) {
        return screenshot(maskSelectors);
    }
}
