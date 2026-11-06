package qaengine.core.evidence;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes sensitive values from text before it leaves the worker. Known secrets go first because
 * an exact match is the strongest signal. This cannot see inside images: masking handles those.
 */
public final class Redactor {

    public static final String MASK = "[REDACTED]";

    private static final Set<String> SENSITIVE_KEYS = Set.of("password", "passwd", "pwd", "secret", "token", "access_token",
            "refresh_token", "id_token", "api_key", "apikey", "authorization", "auth", "cookie", "session", "sessionid", "sid", "otp", "code");

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}"),
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}"),
            Pattern.compile("(?i)basic\\s+[A-Za-z0-9+/=]{8,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),
            Pattern.compile("sk-[A-Za-z0-9_-]{20,}"));

    private static final Pattern KEY_VALUE = Pattern.compile("(?i)([\"']?)([a-z0-9_-]+)\\1(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s&,;}\"']+)");
    private static final Pattern QUERY = Pattern.compile("([?&;])([A-Za-z0-9_-]+)=([^&#;\\s\"'<>]*)");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");

    private final List<String> knownSecrets;

    public Redactor(Collection<String> knownSecrets) {
        this.knownSecrets = knownSecrets.stream().filter(s -> s != null && s.length() >= 4).toList();
    }

    public String redact(String text) {
        if (text == null) return null;
        String out = text;
        for (String secret : knownSecrets) out = out.replace(secret, MASK);
        for (Pattern p : PATTERNS) out = p.matcher(out).replaceAll(MASK);
        out = redactQueries(out);
        out = redactKeyValues(out);
        return redactCards(out);
    }

    /**
     * Separate from key-value matching, which reads "https:" as a key and the rest of the URL as its
     * value, and so would skip any secret in a query string.
     */
    private static String redactQueries(String text) {
        Matcher m = QUERY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(2).toLowerCase(Locale.ROOT).replace('-', '_');
            m.appendReplacement(out, Matcher.quoteReplacement(SENSITIVE_KEYS.contains(key) ? m.group(1) + m.group(2) + "=" + MASK : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String redactKeyValues(String text) {
        Matcher m = KEY_VALUE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(2).toLowerCase(Locale.ROOT).replace('-', '_');
            String replacement = SENSITIVE_KEYS.contains(key)
                    ? m.group(1) + m.group(2) + m.group(1) + m.group(3) + MASK
                    : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String redactCards(String text) {
        Matcher m = CARD.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(luhn(m.group()) ? MASK : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean luhn(String candidate) {
        String digits = candidate.replaceAll("[ -]", "");
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            twice = !twice;
        }
        return sum % 10 == 0;
    }
}
