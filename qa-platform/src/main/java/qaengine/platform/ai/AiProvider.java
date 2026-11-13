package qaengine.platform.ai;

import java.util.Map;

/**
 * One model provider, behind the single call the platform makes: a system prompt, a user prompt, and the JSON schema
 * the answer must follow. Nothing in exploration, the gate or execution ever calls a provider; only {@link AiGateway} does.
 * Adapters never log prompts, answers or keys.
 */
public interface AiProvider {

    String name();

    String model();

    /**
     * @param purpose what the call is for, recorded with its cost, such as "test-design"
     * @param schema  a JSON schema object for the answer; the provider enforces it where it can, and the caller validates it again
     */
    record Request(String purpose, String system, String user, Map<String, Object> schema, long maxOutputTokens) {}

    /** @param json the answer, as text the caller parses and validates. Never trusted until it has been */
    record Completion(String json, String requestId, Long inputTokens, Long outputTokens) {}

    Completion complete(Request request) throws AiException;

    final class AiException extends Exception {

        public enum Kind { REFUSED, TRUNCATED, TIMEOUT, FAILED, INVALID_RESPONSE }

        private final Kind kind;

        /** @param detail fixed wording or a status code: never the prompt, the answer, or a key */
        public AiException(Kind kind, String detail) {
            super(detail);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}
