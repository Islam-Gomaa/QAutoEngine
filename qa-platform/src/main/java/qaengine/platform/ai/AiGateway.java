package qaengine.platform.ai;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one way the platform reaches an AI provider. Off unless a provider and its key are configured; when off, every
 * feature that could use a model falls back to its deterministic rules and says so. Every call is recorded with its
 * outcome, tokens and cost, in its own transaction, so a cost is kept even when the work that asked for it fails.
 * Prompts and answers are never stored or logged: they hold the target application's content.
 */
@Component
public class AiGateway {

    /** What the platform says about its AI, for the API and the dashboard. Never includes the key. */
    public record Status(boolean enabled, String provider, String model, String reason) {}

    private final JdbcClient db;
    private final TransactionTemplate ownTransaction;
    private final AiProvider provider;
    private final Status status;
    private final BigDecimal inputUsdPerMtok;
    private final BigDecimal outputUsdPerMtok;

    public AiGateway(JdbcClient db, PlatformTransactionManager transactions,
                     @Value("${qa.ai.provider:none}") String provider,
                     @Value("${qa.ai.model:claude-opus-5-5}") String model,
                     @Value("${qa.ai.effort:medium}") String effort,
                     @Value("${qa.ai.api-key:}") String apiKey,
                     @Value("${qa.ai.api-key-file:}") String apiKeyFile,
                     @Value("${qa.ai.base-url:}") String baseUrl,
                     @Value("${qa.ai.timeout-seconds:120}") int timeoutSeconds,
                     @Value("${qa.ai.max-retries:2}") int maxRetries,
                     @Value("${qa.ai.input-usd-per-mtok:}") String inputPrice,
                     @Value("${qa.ai.output-usd-per-mtok:}") String outputPrice) {
        this.db = db;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.inputUsdPerMtok = price(inputPrice);
        this.outputUsdPerMtok = price(outputPrice);
        String key = key(apiKey, apiKeyFile);
        String chosen = provider == null ? "none" : provider.strip().toLowerCase(java.util.Locale.ROOT);
        switch (chosen) {
            case "none", "" -> {
                this.provider = null;
                this.status = new Status(false, null, null, "No AI provider is configured. Test design uses its rules only.");
            }
            case "anthropic" -> {
                if (key == null) {
                    this.provider = null;
                    this.status = new Status(false, "anthropic", model, "The Anthropic provider is selected but no API key is set. Test design uses its rules only.");
                } else {
                    this.provider = new AnthropicProvider(key, model, effort, baseUrl, Duration.ofSeconds(timeoutSeconds), maxRetries);
                    this.status = new Status(true, "anthropic", model, "");
                }
            }
            default -> {
                this.provider = null;
                this.status = new Status(false, chosen, model, "Unknown AI provider. Supported: anthropic. Test design uses its rules only.");
            }
        }
    }

    private static String key(String value, String file) {
        if (value != null && !value.isBlank()) return value.strip();
        if (file == null || file.isBlank()) return null;
        try {
            String k = Files.readString(Path.of(file)).strip();
            return k.isEmpty() ? null : k;
        } catch (IOException e) {
            return null; // Reported as "no API key", never with the path or the error.
        }
    }

    private static BigDecimal price(String s) {
        try {
            return s == null || s.isBlank() ? null : new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Status status() {
        return status;
    }

    public boolean enabled() {
        return provider != null;
    }

    /** Calls the provider and records the call. Throws {@link AiProvider.AiException} for any outcome but a usable answer. */
    public AiProvider.Completion call(UUID organizationId, AiProvider.Request request) throws AiProvider.AiException {
        if (provider == null) throw new AiProvider.AiException(AiProvider.AiException.Kind.FAILED, "no AI provider is configured");
        long started = System.nanoTime();
        try {
            AiProvider.Completion c = provider.complete(request);
            record(organizationId, request, "OK", c, started, "");
            return c;
        } catch (AiProvider.AiException e) {
            String outcome = switch (e.kind()) {
                case REFUSED -> "REFUSED";
                case TRUNCATED -> "TRUNCATED";
                case TIMEOUT -> "TIMEOUT";
                case INVALID_RESPONSE -> "INVALID_RESPONSE";
                case FAILED -> "FAILED";
            };
            record(organizationId, request, outcome, null, started, e.getMessage());
            throw e;
        }
    }

    /** Recorded later by the caller when an answer arrived but did not pass validation. */
    public void markInvalid(UUID organizationId, String requestId, String detail) {
        ownTransaction.executeWithoutResult(t -> db.sql("UPDATE ai_call SET outcome = 'INVALID_RESPONSE', detail = ? WHERE organization_id = ? AND request_id = ?")
                .params(cap(detail), organizationId, requestId).update());
    }

    private void record(UUID organizationId, AiProvider.Request request, String outcome, AiProvider.Completion c, long started, String detail) {
        long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
        BigDecimal cost = c == null ? null : cost(c.inputTokens(), c.outputTokens());
        ownTransaction.executeWithoutResult(t -> db.sql("""
                INSERT INTO ai_call (organization_id, id, purpose, provider, model, outcome, request_id, input_tokens, output_tokens, cost_usd, latency_ms, prompt_sha256, detail)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """).params(organizationId, UUID.randomUUID(), request.purpose(), provider.name(), provider.model(), outcome,
                c == null ? null : c.requestId(), c == null ? null : c.inputTokens(), c == null ? null : c.outputTokens(), cost, latency,
                sha256(request.system() + "\u0000" + request.user()), cap(detail)).update());
    }

    private BigDecimal cost(Long in, Long out) {
        if (inputUsdPerMtok == null || outputUsdPerMtok == null || in == null || out == null) return null;
        BigDecimal million = BigDecimal.valueOf(1_000_000);
        return inputUsdPerMtok.multiply(BigDecimal.valueOf(in)).add(outputUsdPerMtok.multiply(BigDecimal.valueOf(out))).divide(million, 6, RoundingMode.HALF_UP);
    }

    private static String cap(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
