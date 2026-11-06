package qaengine.core.policy;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The single enforcement point for a run. Every action and every outgoing request is decided here.
 * Decision order: out of scope, hard deny, matching exception, safe read, otherwise block. Any
 * internal failure blocks.
 */
public final class ActionGate {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final ExecutionPolicy policy;
    private final Clock clock;
    private final Consumer<String> audit;
    private final Instant started;
    private final Map<String, Integer> uses = new HashMap<>();
    private int actions;
    private int loginAttempts;
    private String authWindow;

    public ActionGate(ExecutionPolicy policy, Clock clock, Consumer<String> audit) {
        this.policy = policy;
        this.clock = clock;
        this.audit = audit;
        this.started = clock.instant();
    }

    public ExecutionPolicy policy() {
        return policy;
    }

    public synchronized Decision decideRequest(HttpRequestInfo request) {
        Decision d;
        try {
            d = evaluateRequest(request);
        } catch (RuntimeException e) {
            d = Decision.block(Decision.Reason.POLICY_ERROR, e.getClass().getSimpleName());
        }
        audit.accept("request " + request.method() + " " + safeUrl(request.url()) + " -> " + d.reason());
        return d;
    }

    public synchronized Decision decideAction(ActionRequest action) {
        Decision d;
        try {
            d = evaluateAction(action);
            if (d.allowed()) actions++;
        } catch (RuntimeException e) {
            d = Decision.block(Decision.Reason.POLICY_ERROR, e.getClass().getSimpleName());
        }
        audit.accept("action " + action.kind() + " " + safeUrl(action.url()) + " -> " + d.reason());
        return d;
    }

    /** Opens the login window for one AUTH exception. Attempts are limited to avoid account lockout. */
    public synchronized Decision openAuthWindow(String exceptionId) {
        Optional<ScopedException> found = policy.exceptions().stream()
                .filter(e -> e.id().equals(exceptionId) && e.purpose() == ScopedException.Purpose.AUTH)
                .findFirst();
        if (found.isEmpty()) return Decision.block(Decision.Reason.AUTH_WINDOW_CLOSED, "no such AUTH exception");
        if (loginAttempts >= policy.budget().maxLoginAttempts()) {
            return Decision.block(Decision.Reason.BUDGET_EXCEEDED, "login attempt limit reached");
        }
        loginAttempts++;
        authWindow = exceptionId;
        audit.accept("auth window opened " + exceptionId);
        return Decision.allow(Decision.Reason.EXCEPTION_MATCHED, exceptionId);
    }

    public synchronized void closeAuthWindow() {
        if (authWindow != null) audit.accept("auth window closed " + authWindow);
        authWindow = null;
    }

    public synchronized boolean authWindowOpen() {
        return authWindow != null;
    }

    private Decision evaluateRequest(HttpRequestInfo r) {
        if (budgetSpent()) return Decision.block(Decision.Reason.BUDGET_EXCEEDED, "run budget spent");
        if (!policy.scope().allowsHost(r.url())) return Decision.block(Decision.Reason.OUT_OF_SCOPE, "host not allowed");

        boolean isStatic = r.resource() == HttpRequestInfo.Resource.STATIC;
        String method = RiskRules.effectiveMethod(r);
        String path = pathOf(r.url());
        String origin = TargetScope.originOf(r.url()).orElseThrow();

        Optional<ScopedException> exception = policy.exceptions().stream()
                .filter(e -> e.matches(origin, method, path, r.body()) && active(e))
                .findFirst();

        // Hard deny comes before exceptions, with no carve-outs: a matching exception never helps here.
        Optional<String> risk = RiskRules.urlRisk(r.url(), policy.extraRiskTokens(), !isStatic);
        if (risk.isPresent()) return Decision.block(Decision.Reason.HARD_DENY, risk.get());
        if (looksLikeGraphQl(r) && GraphQlInspector.singleNamedQuery(r.body()).isEmpty()) {
            return Decision.block(Decision.Reason.HARD_DENY, "GraphQL request is not one named query");
        }
        if (exception.isPresent()) {
            ScopedException e = exception.get();
            uses.merge(e.id(), 1, Integer::sum);
            return Decision.allow(Decision.Reason.EXCEPTION_MATCHED, e.id());
        }
        if (r.resource() == HttpRequestInfo.Resource.WEBSOCKET) {
            return Decision.block(Decision.Reason.UNSAFE_METHOD, "WebSocket needs an exception");
        }
        if (SAFE_METHODS.contains(method)) {
            if (!isStatic && !policy.scope().allowsPath(r.url())) {
                return Decision.block(Decision.Reason.OUT_OF_SCOPE, "path not allowed");
            }
            return Decision.allow(Decision.Reason.SAFE_READ, method);
        }
        return Decision.block(Decision.Reason.UNSAFE_METHOD, method);
    }

    private boolean looksLikeGraphQl(HttpRequestInfo r) {
        // A GraphQL endpoint reached without a body is the GET form, which cannot be inspected safely.
        return pathOf(r.url()).toLowerCase(java.util.Locale.ROOT).contains("graphql")
                || (r.body() != null && r.body().contains("\"query\""));
    }

    private boolean active(ScopedException e) {
        if (clock.instant().isAfter(e.expires())) return false;
        if (uses.getOrDefault(e.id(), 0) >= e.maxUses()) return false;
        return e.purpose() != ScopedException.Purpose.AUTH || e.id().equals(authWindow);
    }

    private Decision evaluateAction(ActionRequest a) {
        if (budgetSpent() || actions >= policy.budget().maxActions()) {
            return Decision.block(Decision.Reason.BUDGET_EXCEEDED, "run budget spent");
        }
        if (a.kind() == ActionRequest.Kind.DIALOG_ACCEPT) return Decision.block(Decision.Reason.ACTION_NOT_ALLOWED, "dialogs are always dismissed");
        if (a.kind() == ActionRequest.Kind.UPLOAD || a.kind() == ActionRequest.Kind.DOWNLOAD) {
            return Decision.block(Decision.Reason.ACTION_NOT_ALLOWED, "file transfer");
        }
        if (a.url() != null && !a.url().isEmpty() && !policy.scope().allowsHost(a.url())) {
            return Decision.block(Decision.Reason.OUT_OF_SCOPE, "host not allowed");
        }
        Optional<String> controlRisk = RiskRules.controlRisk(a.attributes(), policy.extraRiskTokens());
        boolean loginStep = authWindow != null && (a.kind() == ActionRequest.Kind.FORM_FILL || a.kind() == ActionRequest.Kind.FORM_SUBMIT);
        if (!loginStep && controlRisk.isPresent()) return Decision.block(Decision.Reason.HARD_DENY, controlRisk.get());

        return switch (a.kind()) {
            case NAVIGATE_LINK -> {
                if (a.url() == null) yield Decision.block(Decision.Reason.ACTION_NOT_ALLOWED, "link has no target");
                Optional<String> risk = RiskRules.urlRisk(a.url(), policy.extraRiskTokens());
                if (risk.isPresent()) yield Decision.block(Decision.Reason.HARD_DENY, risk.get());
                if (!policy.scope().allowsPath(a.url())) yield Decision.block(Decision.Reason.OUT_OF_SCOPE, "path not allowed");
                yield Decision.allow(Decision.Reason.ALLOWED_INTERACTION, "link");
            }
            case DISCLOSURE -> Decision.allow(Decision.Reason.ALLOWED_INTERACTION, "disclosure");
            case API_CALL -> Decision.allow(Decision.Reason.ALLOWED_INTERACTION, "api call");
            case FORM_FILL -> authWindow != null || policy.mode() == ExecutionMode.VALIDATION_ONLY
                    ? Decision.allow(Decision.Reason.ALLOWED_INTERACTION, "fill")
                    : Decision.block(Decision.Reason.ACTION_NOT_ALLOWED, "forms are not filled in READ_ONLY");
            case FORM_SUBMIT -> authWindow != null
                    ? Decision.allow(Decision.Reason.EXCEPTION_MATCHED, authWindow)
                    : Decision.block(Decision.Reason.ACTION_NOT_ALLOWED, "forms are never submitted");
            default -> Decision.block(Decision.Reason.ACTION_NOT_ALLOWED, "not on the interaction allowlist");
        };
    }

    private boolean budgetSpent() {
        return clock.instant().isAfter(started.plus(policy.budget().maxDuration()));
    }

    private static String pathOf(String url) {
        return URI.create(url.trim()).getPath();
    }

    /** Query strings can hold secrets, so audit lines keep the path only. */
    private static String safeUrl(String url) {
        if (url == null) return "-";
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?<redacted>";
    }
}
