package qaengine.core.discovery;

import java.util.List;
import java.util.Map;

/**
 * What one page holds, read in a single pass without touching anything. Bounded, so a page cannot make
 * it arbitrarily large: lists are cut at the limits below, and text at 200 characters.
 */
public record PageInventory(String url, String title, List<String> headings, List<Navigation> navigation, List<Form> forms, List<Table> tables,
                            List<Dialog> dialogs, List<Action> actions) {

    public static final int MAX_ACTIONS = 200, MAX_FORMS = 20, MAX_FIELDS = 50, MAX_TABLES = 20, MAX_DIALOGS = 20, MAX_HEADINGS = 30, MAX_NAVIGATION = 10, MAX_LINKS = 50;

    /** A navigation landmark and the names of its links. */
    public record Navigation(String name, List<String> links) {
        public Navigation {
            name = cut(name);
            links = cap(links, MAX_LINKS).stream().map(PageInventory::cut).toList();
        }
    }

    /** @param labelled false when the field has no accessible name, which is worth knowing on its own */
    public record Field(String label, String name, String type, boolean required, boolean labelled) {
        public Field {
            label = cut(label);
            name = cut(name);
            type = cut(type);
        }
    }

    /** @param action the path the form submits to; never submitted during discovery */
    public record Form(String name, String method, String action, List<Field> fields, String submitLabel) {
        public Form {
            name = cut(name);
            method = cut(method);
            action = cut(action);
            fields = cap(fields, MAX_FIELDS);
            submitLabel = cut(submitLabel);
        }
    }

    public record Table(String caption, List<String> headers, int rows) {
        public Table {
            caption = cut(caption);
            headers = cap(headers, 30).stream().map(PageInventory::cut).toList();
        }
    }

    /** @param open whether it was showing when the page was read; closed ones are listed too */
    public record Dialog(String name, boolean open) {
        public Dialog {
            name = cut(name);
        }
    }

    /**
     * Something a person could use.
     *
     * @param kind       LINK, BUTTON, SUBMIT or DISCLOSURE
     * @param locator    how to find it again: strategy, value and, for a role, name
     * @param attributes the attributes the classifier reads, such as data-method or download
     */
    public record Action(String kind, String role, String name, String href, Map<String, String> locator, Map<String, String> attributes) {
        public Action {
            role = cut(role);
            name = cut(name);
            href = href == null ? null : href.length() > 500 ? href.substring(0, 500) : href;
            locator = locator == null ? Map.of() : Map.copyOf(locator);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public PageInventory {
        title = cut(title);
        headings = cap(headings, MAX_HEADINGS).stream().map(PageInventory::cut).toList();
        navigation = cap(navigation, MAX_NAVIGATION);
        forms = cap(forms, MAX_FORMS);
        tables = cap(tables, MAX_TABLES);
        dialogs = cap(dialogs, MAX_DIALOGS);
        actions = cap(actions, MAX_ACTIONS);
    }

    static String cut(String s) {
        if (s == null) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    static <T> List<T> cap(List<T> list, int max) {
        if (list == null) return List.of();
        return List.copyOf(list.size() > max ? list.subList(0, max) : list);
    }
}
