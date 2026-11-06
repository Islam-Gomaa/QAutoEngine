package qaengine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenApiReaderTest {

    static String spec(String paths, String components) {
        return "{\"openapi\":\"3.0.3\",\"paths\":" + paths + ",\"components\":" + components + "}";
    }

    static final String GET_REF = "{\"/a\":{\"get\":{\"responses\":{\"200\":{\"description\":\"x\",\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"%s\"}}}}}}}}";

    @Test void readsOperations() {
        var ops = OpenApiReader.read(spec("{\"/a/{id}\":{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"integer\"}}],\"get\":{\"security\":[{\"b\":[]}],\"x-requires-role\":\"admin\",\"responses\":{\"200\":{\"description\":\"\"}}},\"post\":{\"responses\":{\"201\":{\"description\":\"\"}}}}}", "{}"));
        assertEquals(2, ops.size());
        var get = ops.stream().filter(o -> o.method().equals("GET")).findFirst().orElseThrow();
        assertTrue(get.secured());
        assertEquals("admin", get.requiredRole());
        assertEquals("id", get.params().getFirst().name());
    }

    @Test void globalSecurityApplies() {
        var ops = OpenApiReader.read("{\"openapi\":\"3.0.0\",\"security\":[{\"b\":[]}],\"paths\":{\"/a\":{\"get\":{\"responses\":{}}},\"/b\":{\"get\":{\"security\":[],\"responses\":{}}}}}");
        assertTrue(ops.stream().filter(o -> o.path().equals("/a")).findFirst().orElseThrow().secured());
        assertTrue(ops.stream().filter(o -> o.path().equals("/b")).noneMatch(ApiOperation::secured));
    }

    @Test void localReferencesAreInlined() {
        var ops = OpenApiReader.read(spec(GET_REF.formatted("#/components/schemas/A"), "{\"schemas\":{\"A\":{\"type\":\"object\",\"properties\":{\"b\":{\"$ref\":\"#/components/schemas/B\"}}},\"B\":{\"type\":\"string\"}}}"));
        assertEquals("string", ops.getFirst().responseFor(200).schema().path("properties").path("b").path("type").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.test/schema.json", "https://evil.test/x#/a", "file:///etc/passwd", "other.json#/a", "//evil.test/x"})
    void externalReferencesAreRefused(String ref) {
        assertThrows(OpenApiReader.SpecRejected.class, () -> OpenApiReader.read(spec(GET_REF.formatted(ref), "{}")));
    }

    @Test void referenceCyclesAreRefused() {
        assertThrows(OpenApiReader.SpecRejected.class, () -> OpenApiReader.read(spec(GET_REF.formatted("#/components/schemas/A"), "{\"schemas\":{\"A\":{\"properties\":{\"a\":{\"$ref\":\"#/components/schemas/A\"}}}}}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.test/x", "//evil.test/x", "/a/../../etc", "relative"})
    void pathsMustBeRelative(String path) {
        assertThrows(OpenApiReader.SpecRejected.class, () -> OpenApiReader.read(spec("{\"" + path + "\":{\"get\":{\"responses\":{}}}}", "{}")));
    }

    @Test void otherInputIsRefused() {
        assertThrows(OpenApiReader.SpecRejected.class, () -> OpenApiReader.read("not json"));
        assertThrows(OpenApiReader.SpecRejected.class, () -> OpenApiReader.read("{\"swagger\":\"2.0\",\"paths\":{}}"));
        assertThrows(OpenApiReader.SpecRejected.class, () -> OpenApiReader.read(null));
        assertThrows(OpenApiReader.SpecRejected.class, () -> OpenApiReader.read(" ".repeat(OpenApiReader.MAX_BYTES + 1)));
    }

    @Test void operationCountIsLimited() {
        StringBuilder paths = new StringBuilder("{");
        for (int i = 0; i < 501; i++) paths.append(i == 0 ? "" : ",").append("\"/p").append(i).append("\":{\"get\":{\"responses\":{}}}");
        assertThrows(OpenApiReader.SpecRejected.class, () -> OpenApiReader.read(spec(paths + "}", "{}")));
    }

    @Test void generatorIsDeterministicAndCoversBoundaries() {
        var op = OpenApiReader.read(spec("{\"/a\":{\"get\":{\"parameters\":[{\"name\":\"n\",\"in\":\"query\",\"required\":true,\"schema\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}},{\"name\":\"s\",\"in\":\"query\",\"schema\":{\"type\":\"string\",\"minLength\":2,\"maxLength\":3}}],\"responses\":{}}}}", "{}")).getFirst();
        var gen = new ScenarioGenerator(false, false);
        var kinds = gen.generate(op).stream().map(ApiScenario::kind).toList();
        assertEquals(kinds, gen.generate(op).stream().map(ApiScenario::kind).toList());
        assertTrue(kinds.containsAll(List.of(ApiScenario.Kind.POSITIVE, ApiScenario.Kind.MISSING_REQUIRED, ApiScenario.Kind.INVALID_TYPE, ApiScenario.Kind.AT_MINIMUM,
                ApiScenario.Kind.BELOW_MINIMUM, ApiScenario.Kind.AT_MAXIMUM, ApiScenario.Kind.ABOVE_MAXIMUM, ApiScenario.Kind.OPTIONS_PROBE, ApiScenario.Kind.HEAD_PARITY)));
        var above = gen.generate(op).stream().filter(s -> s.kind() == ApiScenario.Kind.ABOVE_MAXIMUM && s.queryValues().containsKey("n") && s.queryValues().get("n").equals("11")).toList();
        assertEquals(1, above.size());
    }

    @Test void valuesAreEncoded() {
        assertEquals("/a/x%2Fy?q=a%20b%26c", ApiClient.expand("/a/{id}", java.util.Map.of("id", "x/y"), java.util.Map.of("q", "a b&c")));
    }
}
