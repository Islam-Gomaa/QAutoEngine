package qaengine.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Turns an operation into scenarios by fixed rules, so the same spec always gives the same tests. */
public final class ScenarioGenerator {

    private final boolean hasPrimary;
    private final boolean hasLowPrivilege;

    public ScenarioGenerator(boolean hasPrimary, boolean hasLowPrivilege) {
        this.hasPrimary = hasPrimary;
        this.hasLowPrivilege = hasLowPrivilege;
    }

    public List<ApiScenario> generate(ApiOperation op) {
        List<ApiScenario> out = new ArrayList<>();
        Map<String, String> path = new HashMap<>();
        Map<String, String> query = new HashMap<>();
        for (ApiOperation.Param p : op.params()) {
            if (p.in().equals("path")) path.put(p.name(), validValue(p.schema()));
            else if (p.required() || hasExample(p.schema())) query.put(p.name(), validValue(p.schema()));
        }
        ApiScenario.Credential cred = op.secured() ? ApiScenario.Credential.PRIMARY : ApiScenario.Credential.NONE;
        if (!op.secured() || hasPrimary) {
            out.add(new ApiScenario(ApiScenario.Kind.POSITIVE, op, path, query, cred));
            out.add(new ApiScenario(ApiScenario.Kind.OPTIONS_PROBE, op, path, query, cred));
            if (op.method().equals("GET")) out.add(new ApiScenario(ApiScenario.Kind.HEAD_PARITY, op, path, query, cred));
        }
        for (ApiOperation.Param p : op.params()) inputScenarios(out, op, p, path, query, cred);
        if (op.secured()) {
            out.add(new ApiScenario(ApiScenario.Kind.NO_CREDENTIAL, op, path, query, ApiScenario.Credential.NONE));
            out.add(new ApiScenario(ApiScenario.Kind.INVALID_CREDENTIAL, op, path, query, ApiScenario.Credential.INVALID));
            if (op.requiredRole() != null && hasLowPrivilege) {
                out.add(new ApiScenario(ApiScenario.Kind.LOW_PRIVILEGE, op, path, query, ApiScenario.Credential.LOW_PRIVILEGE));
            }
        }
        return out;
    }

    private void inputScenarios(List<ApiScenario> out, ApiOperation op, ApiOperation.Param p, Map<String, String> path, Map<String, String> query, ApiScenario.Credential cred) {
        JsonNode s = p.schema();
        String type = s.path("type").asString("string");
        if (p.required() && p.in().equals("query")) out.add(without(op, p, path, query, cred));
        if (type.equals("integer") || type.equals("number")) {
            out.add(with(ApiScenario.Kind.INVALID_TYPE, op, p, "not-a-number", path, query, cred));
            if (s.has("minimum")) {
                long min = s.get("minimum").asLong();
                out.add(with(ApiScenario.Kind.AT_MINIMUM, op, p, String.valueOf(min), path, query, cred));
                out.add(with(ApiScenario.Kind.BELOW_MINIMUM, op, p, String.valueOf(min - 1), path, query, cred));
            }
            if (s.has("maximum")) {
                long max = s.get("maximum").asLong();
                out.add(with(ApiScenario.Kind.AT_MAXIMUM, op, p, String.valueOf(max), path, query, cred));
                out.add(with(ApiScenario.Kind.ABOVE_MAXIMUM, op, p, String.valueOf(max + 1), path, query, cred));
            }
        } else if (s.has("enum")) {
            out.add(with(ApiScenario.Kind.INVALID_ENUM, op, p, "not-in-enum", path, query, cred));
        } else if (type.equals("string")) {
            if (s.has("maxLength")) {
                int max = s.get("maxLength").asInt();
                out.add(with(ApiScenario.Kind.AT_MAXIMUM, op, p, "a".repeat(max), path, query, cred));
                out.add(with(ApiScenario.Kind.ABOVE_MAXIMUM, op, p, "a".repeat(max + 1), path, query, cred));
            }
            if (s.path("minLength").asInt(0) > 0) {
                out.add(with(ApiScenario.Kind.BELOW_MINIMUM, op, p, "", path, query, cred));
            }
        }
    }

    private static ApiScenario with(ApiScenario.Kind kind, ApiOperation op, ApiOperation.Param p, String value, Map<String, String> path, Map<String, String> query, ApiScenario.Credential cred) {
        Map<String, String> pv = new HashMap<>(path);
        Map<String, String> qv = new HashMap<>(query);
        (p.in().equals("path") ? pv : qv).put(p.name(), value);
        return new ApiScenario(kind, op, pv, qv, cred);
    }

    private static ApiScenario without(ApiOperation op, ApiOperation.Param p, Map<String, String> path, Map<String, String> query, ApiScenario.Credential cred) {
        Map<String, String> qv = new HashMap<>(query);
        qv.remove(p.name());
        return new ApiScenario(ApiScenario.Kind.MISSING_REQUIRED, op, path, qv, cred);
    }

    private static boolean hasExample(JsonNode s) {
        return s.has("example") || s.has("default");
    }

    static String validValue(JsonNode s) {
        if (s.has("example")) return s.get("example").asString();
        if (s.has("default")) return s.get("default").asString();
        if (s.has("enum") && !s.get("enum").isEmpty()) return s.get("enum").get(0).asString();
        if (s.has("minimum")) return s.get("minimum").asString();
        String type = s.path("type").asString("string");
        if (type.equals("integer") || type.equals("number")) return "1";
        if (type.equals("boolean")) return "true";
        return "test";
    }
}
