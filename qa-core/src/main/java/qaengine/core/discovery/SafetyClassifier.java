package qaengine.core.discovery;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import qaengine.core.policy.RiskRules;

/**
 * Says what an action would do, from its name, its link and its attributes. It never uses the action. When
 * the intent cannot be told, a button is treated as sensitive, not as harmless: being wrong in that
 * direction costs a review, while the other direction could cost data.
 */
public final class SafetyClassifier {

    /** One action and what using it would do. The reason names the word or attribute that decided it. */
    public record Classified(PageInventory.Action action, SafetyClass safety, String reason) {}

    private static final Set<String> DESTRUCTIVE = Set.of("delete", "remove", "destroy", "drop", "purge", "erase", "wipe", "trash", "discard", "revoke", "deactivate",
            "disable", "archive", "reset", "unsubscribe", "void", "terminate", "حذف", "إزالة", "ازالة", "تعطيل", "إيقاف", "ايقاف");
    private static final Set<String> SENSITIVE = Set.of("pay", "payment", "checkout", "purchase", "buy", "order", "invite", "send", "publish", "approve", "reject",
            "confirm", "transfer", "submit", "share", "export", "download", "logout", "logoff", "signout", "subscribe", "charge", "refund", "withdraw", "grant", "cancel",
            "دفع", "تأكيد", "تاكيد", "خروج", "إرسال", "ارسال", "إلغاء", "الغاء");
    private static final Set<String> REVERSIBLE = Set.of("edit", "update", "save", "rename", "change", "modify", "toggle", "activate", "enable", "add", "create", "new",
            "upload", "assign", "move", "restore", "تعديل", "حفظ", "إضافة", "اضافة");
    private static final Set<String> READ_ONLY = Set.of("view", "show", "open", "search", "filter", "sort", "next", "previous", "prev", "more", "details", "expand",
            "collapse", "refresh", "back", "home", "menu", "close", "help", "find", "عرض", "بحث");

    private SafetyClassifier() {}

    public static List<Classified> classify(List<PageInventory.Action> actions) {
        List<Classified> out = new ArrayList<>(actions.size());
        for (PageInventory.Action a : actions) out.add(classify(a));
        return out;
    }

    public static Classified classify(PageInventory.Action a) {
        Map<String, String> attrs = a.attributes();
        String method = attrs.getOrDefault("data-method", "").toLowerCase(Locale.ROOT);
        if (!method.isEmpty() && !method.equals("get")) {
            return new Classified(a, method.equals("delete") ? SafetyClass.DESTRUCTIVE : SafetyClass.SENSITIVE, "sends a " + method.toUpperCase(Locale.ROOT) + " request");
        }
        List<String> words = new ArrayList<>();
        for (String text : List.of(a.name(), attrs.getOrDefault("aria-label", ""), attrs.getOrDefault("title", ""), attrs.getOrDefault("id", ""),
                attrs.getOrDefault("name", ""), attrs.getOrDefault("data-testid", ""))) {
            words.addAll(RiskRules.words(text));
        }
        boolean link = a.kind().equals("LINK");
        if (link && a.href() != null) words.addAll(RiskRules.words(path(a.href())));

        Optional<String> destructive = first(words, DESTRUCTIVE);
        if (destructive.isPresent()) return new Classified(a, SafetyClass.DESTRUCTIVE, "named \"" + destructive.get() + "\"");
        Optional<String> sensitive = first(words, SENSITIVE);
        if (sensitive.isPresent()) return new Classified(a, SafetyClass.SENSITIVE, "named \"" + sensitive.get() + "\"");
        if (attrs.containsKey("download")) return new Classified(a, SafetyClass.SENSITIVE, "downloads a file");
        if (attrs.containsKey("data-confirm") || attrs.getOrDefault("onclick", "").contains("confirm(")) {
            return new Classified(a, SafetyClass.SENSITIVE, "asks for confirmation before acting");
        }
        if (link) {
            if (a.href() != null && RiskRules.urlRisk(a.href(), Set.of()).isPresent()) return new Classified(a, SafetyClass.SENSITIVE, "its link carries a one-time token");
            return new Classified(a, SafetyClass.READ_ONLY, "navigates to another page");
        }
        if (a.kind().equals("DISCLOSURE")) return new Classified(a, SafetyClass.READ_ONLY, "reveals content in place");
        Optional<String> reversible = first(words, REVERSIBLE);
        if (reversible.isPresent()) return new Classified(a, SafetyClass.REVERSIBLE, "named \"" + reversible.get() + "\"");
        if (a.kind().equals("BUTTON")) {
            Optional<String> reading = first(words, READ_ONLY);
            if (reading.isPresent()) return new Classified(a, SafetyClass.READ_ONLY, "named \"" + reading.get() + "\"");
        }
        return new Classified(a, SafetyClass.SENSITIVE, a.kind().equals("SUBMIT") ? "submits a form whose intent is not clear from its name" : "its intent is not clear from its name");
    }

    private static Optional<String> first(List<String> words, Set<String> set) {
        for (int i = 0; i < words.size(); i++) {
            if (set.contains(words.get(i))) return Optional.of(words.get(i));
            if (i + 1 < words.size() && set.contains(words.get(i) + words.get(i + 1))) return Optional.of(words.get(i) + words.get(i + 1));
        }
        return Optional.empty();
    }

    private static String path(String href) {
        try {
            URI u = new URI(href);
            return (u.getRawPath() == null ? "" : u.getRawPath()) + (u.getRawQuery() == null ? "" : " " + u.getRawQuery());
        } catch (Exception e) {
            return href;
        }
    }
}
