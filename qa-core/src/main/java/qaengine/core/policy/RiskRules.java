package qaengine.core.policy;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static signals that a request or control changes state. These are defence in depth: the
 * interaction allowlist and request policy still apply when nothing here matches.
 */
public final class RiskRules {

    private static final Set<String> TOKENS = Set.of(
            "delete", "remove", "destroy", "drop", "purge", "erase", "wipe", "trash", "discard",
            "logout", "logoff", "signout", "unsubscribe", "cancel", "void", "revoke", "disable",
            "deactivate", "activate", "reset", "archive", "approve", "reject", "pay", "payment",
            "checkout", "purchase", "confirm", "toggle", "submit", "publish", "download", "export",
            "حذف", "إلغاء", "الغاء", "إزالة", "ازالة", "خروج", "دفع", "تأكيد", "تاكيد", "إيقاف", "ايقاف", "تعطيل");

    private static final Set<String> OVERRIDE_HEADERS =
            Set.of("x-http-method-override", "x-method-override", "x-http-method");

    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z])(?=[A-Z])");
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern LONG_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{24,}$");

    private RiskRules() {}

    /** Returns the reason this URL looks state-changing, using configured extra tokens too. */
    public static Optional<String> urlRisk(String url, Set<String> extraTokens) {
        return urlRisk(url, extraTokens, true);
    }

    /** Static assets may have long hashed names, so only they skip the one-time token check. */
    public static Optional<String> urlRisk(String url, Set<String> extraTokens, boolean checkTokens) {
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            return Optional.of("unparseable URL");
        }
        List<String> parts = new ArrayList<>();
        if (uri.getRawPath() != null) parts.addAll(List.of(uri.getRawPath().split("/")));
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) parts.addAll(List.of(pair.split("=", 2)));
        }
        for (String raw : parts) {
            String part = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            if (checkTokens && isOneTimeToken(part)) return Optional.of("one-time token");
            List<String> words = words(part);
            for (int i = 0; i < words.size(); i++) {
                String w = words.get(i);
                if (TOKENS.contains(w) || extraTokens.contains(w)) return Optional.of("risk token: " + w);
                if (i + 1 < words.size()) {
                    String joined = w + words.get(i + 1);
                    if (TOKENS.contains(joined)) return Optional.of("risk token: " + joined);
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> words(String text) {
        String spaced = CAMEL.matcher(text).replaceAll(" ");
        List<String> out = new ArrayList<>();
        for (String w : SPLIT.split(spaced)) {
            if (!w.isEmpty()) out.add(w.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static boolean isOneTimeToken(String part) {
        if (!LONG_TOKEN.matcher(part).matches()) return false;
        return part.chars().anyMatch(Character::isDigit) && part.chars().anyMatch(Character::isLetter);
    }

    /** The method the server will actually apply, honouring override conventions. */
    public static String effectiveMethod(HttpRequestInfo r) {
        for (var h : r.headers().entrySet()) {
            if (OVERRIDE_HEADERS.contains(h.getKey().toLowerCase(Locale.ROOT))) return h.getValue().toUpperCase(Locale.ROOT);
        }
        return override(queryOf(r.url())).or(() -> override(r.body())).orElse(r.method().toUpperCase(Locale.ROOT));
    }

    private static String queryOf(String url) {
        int i = url.indexOf('?');
        return i < 0 ? "" : url.substring(i + 1);
    }

    private static Optional<String> override(String form) {
        if (form == null) return Optional.empty();
        for (String pair : form.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals("_method")) return Optional.of(kv[1].toUpperCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    /** Attributes on a control that mean it does more than navigate. */
    public static Optional<String> controlRisk(Map<String, String> attributes, Set<String> extraTokens) {
        String method = attributes.getOrDefault("data-method", "").toLowerCase(Locale.ROOT);
        if (!method.isEmpty() && !method.equals("get")) return Optional.of("data-method=" + method);
        if (attributes.containsKey("data-confirm")) return Optional.of("data-confirm");
        if (attributes.containsKey("download")) return Optional.of("download attribute");
        if (attributes.getOrDefault("onclick", "").contains("confirm(")) return Optional.of("confirm() in onclick");
        for (String key : List.of("aria-label", "title", "text", "name", "id", "class")) {
            for (String w : words(attributes.getOrDefault(key, ""))) {
                if (TOKENS.contains(w) || extraTokens.contains(w)) return Optional.of("risk label: " + w);
            }
        }
        return Optional.empty();
    }
}
