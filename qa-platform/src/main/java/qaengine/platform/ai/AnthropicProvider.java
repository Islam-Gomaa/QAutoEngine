package qaengine.platform.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Claude, through Anthropic's official Java SDK. The answer is constrained to the caller's JSON schema by structured
 * outputs, and checked again by the caller. A refusal or a truncated answer is reported as such and never parsed.
 *
 * <p>Server-side refusal fallbacks are on: if the model declines, the API may answer with another model instead
 * (beta {@code server-side-fallback-2026-07-01}, {@code fallbacks: "default"}). The model that answered is recorded.
 */
final class AnthropicProvider implements AiProvider {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final String model;
    private final BetaOutputConfig.Effort effort;

    /** @param baseUrl null for the Anthropic API; set only to point tests at a local stand-in */
    AnthropicProvider(String apiKey, String model, String effort, String baseUrl, Duration timeout, int maxRetries) {
        var builder = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).maxRetries(maxRetries);
        if (baseUrl != null && !baseUrl.isBlank()) builder.baseUrl(baseUrl);
        this.client = builder.build();
        this.model = model;
        this.effort = BetaOutputConfig.Effort.of(effort);
    }

    @Override public String name() {
        return "anthropic";
    }

    @Override public String model() {
        return model;
    }

    /** A timeout shows up as a cause, under whichever exception the HTTP client chose to wrap it in. */
    static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.io.InterruptedIOException) return true;
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (m.contains("timeout") || m.contains("timed out")) return true;
        }
        return false;
    }

    @Override
    public Completion complete(Request request) throws AiException {
        Map<String, JsonValue> schema = new LinkedHashMap<>();
        request.schema().forEach((k, v) -> schema.put(k, JsonValue.from(v)));
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxOutputTokens())
                .system(request.system())
                .addUserMessage(request.user())
                .outputConfig(BetaOutputConfig.builder()
                        // Set explicitly: the default differs between models.
                        .effort(effort)
                        .format(BetaJsonOutputFormat.builder().schema(BetaJsonOutputFormat.Schema.builder().putAllAdditionalProperties(schema).build()).build())
                        .build())
                .addBeta(FALLBACK_BETA)
                .fallbacks(BetaFallbacksParam.ofDefault())
                .build();
        BetaMessage message;
        try {
            message = client.beta().messages().create(params);
        } catch (AnthropicServiceException e) {
            throw new AiException(AiException.Kind.FAILED, "the provider answered " + e.statusCode());
        } catch (AnthropicIoException e) {
            throw new AiException(timedOut(e) ? AiException.Kind.TIMEOUT : AiException.Kind.FAILED, timedOut(e) ? "the provider did not answer in time" : "the provider could not be reached");
        } catch (AnthropicException e) {
            throw new AiException(AiException.Kind.FAILED, "the provider call failed: " + e.getClass().getSimpleName());
        }
        BetaStopReason stop = message.stopReason().orElse(null);
        if (BetaStopReason.REFUSAL.equals(stop)) throw new AiException(AiException.Kind.REFUSED, "the model declined the request");
        if (BetaStopReason.MAX_TOKENS.equals(stop)) throw new AiException(AiException.Kind.TRUNCATED, "the answer was cut off at the output limit");
        String text = message.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).collect(Collectors.joining());
        if (text.isBlank()) throw new AiException(AiException.Kind.INVALID_RESPONSE, "the answer held no text");
        return new Completion(text, message.id(), message.usage().inputTokens(), message.usage().outputTokens());
    }
}
