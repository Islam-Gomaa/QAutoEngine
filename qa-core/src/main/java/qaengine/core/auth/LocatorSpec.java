package qaengine.core.auth;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import qaengine.core.policy.PolicyException;

/**
 * How to find one element, in the order of preference a tester would use: by accessible role and
 * name, label, placeholder or test id first, and a CSS selector only when nothing else is stable.
 *
 * @param value the role for ROLE, otherwise the label, placeholder, test id or selector
 * @param name  the accessible name for ROLE; ignored for the others
 */
public record LocatorSpec(Strategy strategy, String value, String name) {

    public enum Strategy { ROLE, LABEL, PLACEHOLDER, TEST_ID, CSS }

    /** Roles that a login form, a success marker or an error message can sensibly have. */
    static final Set<String> ROLES = Set.of("alert", "banner", "button", "checkbox", "combobox", "dialog", "heading", "img", "link", "main",
            "menu", "menuitem", "navigation", "region", "status", "tab", "textbox");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    public LocatorSpec {
        if (strategy == null) throw new PolicyException("A locator needs a strategy");
        value = value == null ? "" : value.strip();
        name = name == null || name.isBlank() ? null : name.strip();
        if (value.isEmpty() || value.length() > 200 || CONTROL.matcher(value).find()) throw new PolicyException("A locator needs a value of up to 200 characters");
        if (name != null && (name.length() > 200 || CONTROL.matcher(name).find())) throw new PolicyException("A locator name can have up to 200 characters");
        if (strategy == Strategy.ROLE) {
            value = value.toLowerCase(Locale.ROOT);
            if (!ROLES.contains(value)) throw new PolicyException("Unsupported role for a locator");
        }
    }

    public static LocatorSpec of(Strategy strategy, String value) {
        return new LocatorSpec(strategy, value, null);
    }

    /** For logs and evidence: says how the element was found, never what was typed into it. */
    public String describe() {
        return switch (strategy) {
            case ROLE -> "role " + value + (name == null ? "" : " named \"" + name + "\"");
            case LABEL -> "label \"" + value + "\"";
            case PLACEHOLDER -> "placeholder \"" + value + "\"";
            case TEST_ID -> "test id \"" + value + "\"";
            case CSS -> "selector " + value;
        };
    }
}
