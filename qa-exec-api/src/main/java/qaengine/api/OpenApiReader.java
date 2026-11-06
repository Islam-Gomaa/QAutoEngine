package qaengine.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads an OpenAPI 3 document. A spec comes from the target, so it is untrusted: {@code servers} is
 * ignored, only local references are followed, depth and size are limited, and paths must be
 * relative. A general parser was not used because it can fetch remote references outside the gate.
 */
public final class OpenApiReader {

    /** The spec cannot be used safely. */
    public static final class SpecRejected extends RuntimeException {
        public SpecRejected(String reason) {
            super(reason);
        }
    }

    public static final int MAX_BYTES = 2 << 20;
    private static final int MAX_REF_DEPTH = 24;
    private static final int MAX_OPERATIONS = 500;
    private static final Set<String> METHODS = Set.of("get", "head", "post", "put", "patch", "delete");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private OpenApiReader() {}

    public static List<ApiOperation> read(String text) {
        if (text == null || text.length() > MAX_BYTES) throw new SpecRejected("spec is missing or too large");
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JacksonException e) {
            throw new SpecRejected("spec is not valid JSON");
        }
        if (!root.path("openapi").asString("").startsWith("3.")) throw new SpecRejected("only OpenAPI 3 is supported");
        boolean securedByDefault = root.path("security").isArray() && !root.path("security").isEmpty();

        List<ApiOperation> operations = new ArrayList<>();
        JsonNode paths = root.path("paths");
        for (String path : paths.propertyNames()) {
            requireRelative(path);
            JsonNode item = paths.get(path);
            List<ApiOperation.Param> shared = params(item.path("parameters"), root);
            for (String key : item.propertyNames()) {
                if (!METHODS.contains(key)) continue;
                if (operations.size() >= MAX_OPERATIONS) throw new SpecRejected("too many operations");
                operations.add(operation(root, path, key, item.get(key), shared, securedByDefault));
            }
        }
        return operations;
    }

    private static void requireRelative(String path) {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("..") || path.contains("://")) {
            throw new SpecRejected("path must be relative: " + path);
        }
    }

    private static ApiOperation operation(JsonNode root, String path, String method, JsonNode op, List<ApiOperation.Param> shared, boolean securedByDefault) {
        List<ApiOperation.Param> params = new ArrayList<>(shared);
        params.addAll(params(op.path("parameters"), root));
        Map<String, ApiOperation.Response> responses = new LinkedHashMap<>();
        JsonNode declared = op.path("responses");
        for (String status : declared.propertyNames()) responses.put(status, response(declared.get(status), root));
        boolean secured = op.has("security") ? !op.path("security").isEmpty() : securedByDefault;
        JsonNode requestSchema = inline(op.path("requestBody").path("content").path("application/json").path("schema"), root, 0);
        return new ApiOperation(method.toUpperCase(java.util.Locale.ROOT), path, op.path("operationId").asString(path), params, responses, secured,
                op.path("x-requires-role").asString(null), requestSchema);
    }

    private static ApiOperation.Response response(JsonNode r, JsonNode root) {
        JsonNode content = inline(r, root, 0).path("content");
        for (String media : content.propertyNames()) {
            return new ApiOperation.Response(media, inline(content.get(media).path("schema"), root, 0));
        }
        return new ApiOperation.Response(null, null);
    }

    private static List<ApiOperation.Param> params(JsonNode list, JsonNode root) {
        List<ApiOperation.Param> out = new ArrayList<>();
        for (JsonNode raw : list) {
            JsonNode p = inline(raw, root, 0);
            String in = p.path("in").asString("");
            if (in.equals("path") || in.equals("query")) {
                out.add(new ApiOperation.Param(p.path("name").asString(), in, p.path("required").asBoolean(false), inline(p.path("schema"), root, 0)));
            }
        }
        return out;
    }

    /** Replaces local references. External ones are refused, and a cycle hits the depth limit. */
    static JsonNode inline(JsonNode node, JsonNode root, int depth) {
        if (depth > MAX_REF_DEPTH) throw new SpecRejected("references are too deep");
        if (node.isObject()) {
            if (node.has("$ref")) {
                String ref = node.get("$ref").asString("");
                if (!ref.startsWith("#/")) throw new SpecRejected("external references are not followed: " + ref);
                JsonNode target = root.at(ref.substring(1));
                if (target.isMissingNode()) throw new SpecRejected("unresolved reference: " + ref);
                return inline(target, root, depth + 1);
            }
            ObjectNode copy = JSON.createObjectNode();
            for (String name : node.propertyNames()) {
                // $schema, $id and friends can name URLs. Nothing here needs them, so they never reach the validator.
                if (!name.startsWith("$")) copy.set(name, inline(node.get(name), root, depth + 1));
            }
            return copy;
        }
        if (node.isArray()) {
            var copy = JSON.createArrayNode();
            for (JsonNode child : node) copy.add(inline(child, root, depth + 1));
            return copy;
        }
        return node;
    }
}
