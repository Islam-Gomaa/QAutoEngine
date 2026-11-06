package qaengine.fixtures;

import java.util.List;
import java.util.stream.Collectors;

/** Ground truth for the fixture. Engine output is scored against this list. */
public final class Scorecard {

    public enum Kind { DEFECT, TRAP, RESIDUAL_RISK }

    /** @param path page or endpoint where the item lives; @param detail what must happen. */
    public record Item(String id, Kind kind, String path, String detail) {}

    public static final List<Item> ITEMS = List.of(
            new Item("D-404", Kind.DEFECT, "/defects/links", "Link to a 404 page"),
            new Item("D-500", Kind.DEFECT, "/defects/links", "Link to a 500 page"),
            new Item("D-JS", Kind.DEFECT, "/defects/js-error", "Uncaught JavaScript exception"),
            new Item("D-CONSOLE", Kind.DEFECT, "/defects/console-error", "console.error during load"),
            new Item("D-XHR", Kind.DEFECT, "/defects/failed-xhr", "First-party request returns 500"),
            new Item("D-IMG", Kind.DEFECT, "/defects/broken-image", "First-party image returns 404"),
            new Item("T-DELETE-ICON", Kind.TRAP, "/m/delete-account", "Unlabelled icon button"),
            new Item("T-PAY", Kind.TRAP, "/m/confirm-payment", "Confirm payment button"),
            new Item("T-REMOVE", Kind.TRAP, "/m/remove-user", "Remove user button"),
            new Item("T-GET-DELETE", Kind.TRAP, "/items/5/delete", "Destructive GET in path"),
            new Item("T-GET-ACTION", Kind.TRAP, "/items?action=remove&id=5", "Destructive GET in query"),
            new Item("T-LOGOUT", Kind.TRAP, "/logout", "Logout link"),
            new Item("T-DATA-METHOD", Kind.TRAP, "/items/9", "Link with data-method=delete"),
            new Item("T-CONFIRM", Kind.TRAP, "/items/7/reset", "Link guarded by confirm()"),
            new Item("T-TOKEN", Kind.TRAP, "/verify/9f8e7d6c5b4a39281706f5e4d3c2b1a0", "One-time token link"),
            new Item("T-OVERRIDE", Kind.TRAP, "/m/override", "POST with _method override"),
            new Item("T-GRAPHQL", Kind.TRAP, "/graphql", "GraphQL mutation"),
            new Item("T-FORM", Kind.TRAP, "/submitted", "Form submission in READ_ONLY"),
            new Item("T-DOWNLOAD", Kind.TRAP, "/download/report.bin", "File download"),
            new Item("T-EXTERNAL", Kind.TRAP, "http://blocked.invalid/", "Cross-host link"),
            new Item("T-HOSTILE-REDIRECT", Kind.TRAP, "/redirect-external", "Redirect to another host"),
            new Item("R-UNMARKED-GET", Kind.RESIDUAL_RISK, "/tools/nightly", "Unmarked destructive GET; documented residual risk"));

    /** Pages that must produce no findings. */
    public static final List<String> CLEAN_PAGES = List.of("/", "/about", "/team", "/docs", "/faq");

    private Scorecard() {}

    public static List<Item> of(Kind kind) {
        return ITEMS.stream().filter(i -> i.kind() == kind).toList();
    }

    public static String toJson() {
        return ITEMS.stream()
                .map(i -> "  {\"id\":\"%s\",\"kind\":\"%s\",\"path\":\"%s\",\"detail\":\"%s\"}"
                        .formatted(i.id(), i.kind(), i.path(), i.detail()))
                .collect(Collectors.joining(",\n", "[\n", "\n]\n"));
    }
}
