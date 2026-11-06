package qaengine.api;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates a body against a schema from the target. Schemas are untrusted: references are already
 * inlined, and each check has a time limit in case a {@code pattern} is built to run forever.
 */
final class SchemaChecker {

    /** @param unusable the check could not be completed, so no verdict may rest on it */
    record Result(boolean valid, boolean unusable, String detail) {}

    private static final long LIMIT_MILLIS = 2000;
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private SchemaChecker() {}

    static Optional<JsonNode> parse(String body) {
        try {
            return Optional.of(JSON.readTree(body));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    static Result check(JsonNode schema, JsonNode document) {
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        var task = pool.submit(() -> {
            Schema compiled = REGISTRY.getSchema(schema);
            return compiled.validate(document);
        });
        try {
            List<Error> errors = task.get(LIMIT_MILLIS, TimeUnit.MILLISECONDS);
            if (errors.isEmpty()) return new Result(true, false, "");
            Error first = errors.getFirst();
            // The keyword and location only: messages can repeat values from the response.
            return new Result(false, false, first.getInstanceLocation() + " failed " + first.getMessageKey());
        } catch (TimeoutException e) {
            task.cancel(true);
            return new Result(false, true, "schema check timed out");
        } catch (ExecutionException e) {
            return new Result(false, true, "schema could not be used");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, true, "interrupted");
        } finally {
            pool.shutdownNow();
        }
    }
}
