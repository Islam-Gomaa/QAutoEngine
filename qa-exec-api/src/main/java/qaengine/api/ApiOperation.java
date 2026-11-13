package qaengine.api;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * One documented operation. Schemas have every local reference already inlined.
 *
 * @param responses status code to response, with "default" allowed
 * @param requiredRole the {@code x-requires-role} extension, null when any valid credential is enough
 */
public record ApiOperation(String method, String path, String operationId, List<Param> params, Map<String, Response> responses,
                           boolean secured, String requiredRole, JsonNode requestSchema) {

    /** @param in "path" or "query" */
    public record Param(String name, String in, boolean required, JsonNode schema) {}

    /** @param mediaType null when the response has no declared body */
    public record Response(String mediaType, JsonNode schema) {}

    public ApiOperation {
        params = List.copyOf(params);
        responses = Map.copyOf(responses);
    }

    public String label() {
        return method + " " + path;
    }

    public boolean declares(int status) {
        return responses.containsKey(String.valueOf(status)) || responses.containsKey(status / 100 + "XX") || responses.containsKey("default");
    }

    public Response responseFor(int status) {
        Response r = responses.get(String.valueOf(status));
        return r != null ? r : responses.get("default");
    }
}
