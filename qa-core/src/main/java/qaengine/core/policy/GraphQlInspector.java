package qaengine.core.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads just enough of a GraphQL request to prove it is one named query. Anything it cannot prove
 * is treated as unsafe, including batches and persisted queries with no query text.
 */
public final class GraphQlInspector {

    private static final Pattern QUERY_FIELD = Pattern.compile("\"query\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern OPERATION = Pattern.compile("\\b(query|mutation|subscription)\\b\\s*([A-Za-z_][A-Za-z0-9_]*)?");

    private GraphQlInspector() {}

    /** The operation name, only when the body is exactly one named query. */
    public static Optional<String> singleNamedQuery(String body) {
        if (body == null) return Optional.empty();
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) return Optional.empty();
        Matcher field = QUERY_FIELD.matcher(trimmed);
        if (!field.find()) return Optional.empty();
        String document = stripStrings(field.group(1));
        if (field.find()) return Optional.empty();
        List<String[]> operations = new ArrayList<>();
        Matcher op = OPERATION.matcher(topLevel(document));
        while (op.find()) operations.add(new String[] {op.group(1), op.group(2)});
        if (operations.size() != 1) return Optional.empty();
        String[] only = operations.getFirst();
        return only[0].equals("query") && only[1] != null ? Optional.of(only[1]) : Optional.empty();
    }

    private static String stripStrings(String document) {
        return document.replaceAll("\\\\\".*?\\\\\"", "\"\"");
    }

    /** Text outside any selection set, so a field named "mutation" is not mistaken for an operation. */
    private static String topLevel(String document) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (char c : document.toCharArray()) {
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (depth == 0) out.append(c);
        }
        return out.toString();
    }
}
