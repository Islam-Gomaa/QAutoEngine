package qaengine.fixtures;

import java.util.List;
import java.util.stream.Collectors;

/** Ground truth for the fixture. Engine output is scored against this list. */
public final class Scorecard {

    public enum Kind { DEFECT, TRAP, RESIDUAL_RISK, API_DEFECT, API_TRAP }

    /** @param path page or endpoint where the item lives; @param detail what must happen. */
    public record Item(String id, Kind kind, String path, String detail) {}

    public static final List<Item> ITEMS = List.of(
            new Item("D-404", Kind.DEFECT, "/defects/links", "Link to a 404 page"),
            new Item("D-500", Kind.DEFECT, "/defects/links", "Link to a 500 page"),
            new Item("D-JS", Kind.DEFECT, "/defects/js-error", "Uncaught JavaScript exception"),
            new Item("D-CONSOLE", Kind.DEFECT, "/defects/console-error", "console.error during load"),
            new Item("D-XHR", Kind.DEFECT, "/defects/failed-xhr", "First-party request returns 500"),
            new Item("D-IMG", Kind.DEFECT, "/defects/broken-image", "First-party image returns 404"),
            new Item("D-HOSTILE", Kind.DEFECT, "/hostile", "console.error with a script-injection payload, for dashboard escaping"),
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
            new Item("R-UNMARKED-GET", Kind.RESIDUAL_RISK, "/tools/nightly", "Unmarked destructive GET; documented residual risk"),
            new Item("A-500", Kind.API_DEFECT, "/api/v1/search", "Over-long input returns 500"),
            new Item("A-SCHEMA", Kind.API_DEFECT, "/api/v1/orders/{id}", "total is a string, schema says number"),
            new Item("A-CONTENT-TYPE", Kind.API_DEFECT, "/api/v1/status", "Declared JSON, returns HTML"),
            new Item("A-STATUS", Kind.API_DEFECT, "/api/v1/reports/{id}", "Returns undeclared 202"),
            new Item("A-AUTH-BYPASS", Kind.API_DEFECT, "/api/v1/admin/users", "Works with no credentials"),
            new Item("A-AUTHZ", Kind.API_DEFECT, "/api/v1/admin/audit", "Low-privilege token allowed"),
            new Item("A-BOUNDARY", Kind.API_DEFECT, "/api/v1/limits", "Accepts a value above the maximum"),
            new Item("A-LEAK", Kind.API_DEFECT, "/api/v1/debug", "Error response exposes a stack trace"),
            new Item("A-SLOW", Kind.API_DEFECT, "/api/v1/slow", "Over the response-time limit"),
            new Item("A-HEADERS", Kind.API_DEFECT, "/api/v1/public", "Missing X-Content-Type-Options"),
            new Item("AT-POST", Kind.API_TRAP, "POST /api/v1/items", "Documented create"),
            new Item("AT-DELETE", Kind.API_TRAP, "DELETE /api/v1/items/", "Documented delete"),
            new Item("AT-PUT", Kind.API_TRAP, "PUT /api/v1/items/", "Documented replace"),
            new Item("AT-PURGE", Kind.API_TRAP, "GET /api/v1/items/purge", "Documented destructive GET"),
            new Item("AT-SERVER", Kind.API_TRAP, "http://blocked.invalid/", "servers[] and redirect to another host"));

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
