package qaengine.core.script;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.evidence.EvidenceCollector;
import qaengine.core.evidence.EvidenceType;
import qaengine.core.model.Navigation;
import qaengine.core.policy.ActionGate;
import qaengine.core.policy.ActionRequest;
import qaengine.core.policy.Decision;
import qaengine.core.port.BrowserPort;
import qaengine.core.verdict.Verdict;

/**
 * Runs one executable test case in a browser the caller has already signed in, if signing in was needed.
 *
 * <p>Every action is decided by the run's {@link ActionGate}, with what the element really is rather than what the
 * script calls it: a script that says "use" a delete button is refused as the button it is. Expectations only read.
 * A case passes only when every step passed and it made at least one expectation: a page that loads is not a pass.
 * The first step that does not pass ends the case, and says why in a {@link FailureClass}. Nothing is retried except
 * a page that could not be reached at all, once, since that says nothing about the application.
 */
public final class ScriptRunner {

    public record StepResult(int sequence, int index, ScriptStep step, Verdict verdict, FailureClass failure, String note, long durationMs) {}

    /** @param failure null when the case passed */
    public record Result(Verdict verdict, FailureClass failure, String summary, List<StepResult> steps) {}

    public interface Listener {
        void step(StepResult result);
    }

    private static final int MAX_REDIRECTS = 3;
    private static final Set<String> NEVER_FILLED = Set.of("password", "hidden", "file");
    private static final Set<String> DISCLOSURE_ROLES = Set.of("tab", "menuitem");

    private final ActionGate gate;
    private final BrowserPort browser;
    private final URI base;
    private final String loginPath;
    private final Duration wait;
    private final EvidenceCollector evidence;
    private final List<String> masks;
    private final IntSupplier sequences;
    private final Clock clock;
    private BooleanSupplier stopRequested = () -> false;
    private Listener listener = r -> { };

    /**
     * @param baseUrl   the environment's base URL; every path is opened on its origin
     * @param loginPath where the application sends a visitor who is not signed in, or null
     * @param evidence  where screenshots go, one per step, or null for none
     * @param sequences numbers each step within the run, so steps and their evidence line up
     */
    public ScriptRunner(ActionGate gate, BrowserPort browser, String baseUrl, String loginPath, Duration wait,
                        EvidenceCollector evidence, List<String> masks, IntSupplier sequences, Clock clock) {
        this.gate = gate;
        this.browser = browser;
        this.base = URI.create(baseUrl);
        this.loginPath = loginPath == null || loginPath.isBlank() ? null : loginPath;
        this.wait = wait;
        this.evidence = evidence;
        this.masks = List.copyOf(masks);
        this.sequences = sequences;
        this.clock = clock;
    }

    public ScriptRunner stopWhen(BooleanSupplier stop) {
        this.stopRequested = stop;
        return this;
    }

    public ScriptRunner onStep(Listener listener) {
        this.listener = listener;
        return this;
    }

    public Result run(Script script) {
        script.validated();
        List<StepResult> results = new ArrayList<>();
        for (int i = 0; i < script.steps().size(); i++) {
            ScriptStep step = script.steps().get(i);
            if (stopRequested.getAsBoolean()) {
                return finish(results, Verdict.INCONCLUSIVE, FailureClass.INSUFFICIENT_EVIDENCE, "The run stopped before step " + (i + 1));
            }
            long started = clock.millis();
            Outcome o;
            try {
                o = perform(step);
            } catch (RuntimeException e) {
                // A browser error says nothing reliable about the application. Never a pass, never a defect.
                o = new Outcome(Verdict.INCONCLUSIVE, FailureClass.AUTOMATION_FAILURE, "the browser could not perform the step: " + e.getClass().getSimpleName());
            }
            StepResult r = new StepResult(sequences.getAsInt(), i + 1, step, o.verdict, o.failure, o.note, clock.millis() - started);
            results.add(r);
            listener.step(r);
            capture(r);
            if (o.verdict != Verdict.PASS) {
                return finish(results, o.verdict, o.failure, "Step " + (i + 1) + ", " + step.describe() + ": " + o.note);
            }
        }
        return finish(results, Verdict.PASS, null, "All " + script.expectations() + " expectations held");
    }

    private Result finish(List<StepResult> results, Verdict verdict, FailureClass failure, String summary) {
        return new Result(verdict, failure, summary, List.copyOf(results));
    }

    private void capture(StepResult r) {
        if (evidence == null) return;
        try {
            evidence.binary(EvidenceType.SCREENSHOT, r.sequence(), browser.screenshot(masks));
            if (r.verdict() != Verdict.PASS) evidence.text(EvidenceType.DOM_SNAPSHOT, r.sequence(), browser::domSnapshot);
        } catch (RuntimeException e) {
            // Missing evidence never changes a verdict.
        }
    }

    private record Outcome(Verdict verdict, FailureClass failure, String note) {
        static Outcome pass(String note) {
            return new Outcome(Verdict.PASS, null, note);
        }
    }

    private Outcome perform(ScriptStep s) {
        return switch (s.op()) {
            case OPEN -> open(base.resolve(s.path()).toString(), 0);
            case ACTIVATE -> activate(s.target());
            case FILL -> fill(s.target(), s.value());
            case EXPECT_URL -> expectUrl(s.value());
            case EXPECT_TITLE -> {
                String title = browser.title();
                yield title != null && title.contains(s.value()) ? Outcome.pass("title is \"" + title + "\"")
                        : unexpected("the title is \"" + (title == null ? "" : title) + "\"");
            }
            case EXPECT_VISIBLE -> browser.waitVisible(s.target(), wait) ? Outcome.pass("visible")
                    : unexpected(s.target().describe() + " did not appear within " + wait.toSeconds() + "s");
            case EXPECT_TEXT -> expectText(s.target(), s.value());
            case EXPECT_MIN_COUNT -> expectCount(s.target(), s.count());
            case EXPECT_INVALID -> {
                if (!browser.waitVisible(s.target(), wait)) yield new Outcome(Verdict.INCONCLUSIVE, FailureClass.AUTOMATION_FAILURE, s.target().describe() + " was not found");
                yield browser.isInvalid(s.target()) ? Outcome.pass("the field refuses its value") : unexpected("the field accepts its value");
            }
            case EXPECT_STATE -> expectState(s.target(), s.value());
        };
    }

    private static Outcome unexpected(String note) {
        return new Outcome(Verdict.FAIL, FailureClass.UNEXPECTED_BEHAVIOR, note);
    }

    private static Outcome blocked(Decision d) {
        return new Outcome(Verdict.BLOCKED, FailureClass.POLICY_BLOCKED, d.reason() + ": " + d.detail());
    }

    private Outcome open(String url, int hops) {
        Decision d = gate.decideAction(new ActionRequest(ActionRequest.Kind.NAVIGATE_LINK, url, Map.of()));
        if (!d.allowed()) return blocked(d);
        browser.drainSignals();
        Navigation nav = browser.open(url);
        if (nav.outcome() == Navigation.Outcome.FAILED) {
            // The one retry: an unreachable page may be a moment's network trouble. A page that answered is never retried.
            nav = browser.open(url);
        }
        return switch (nav.outcome()) {
            case FAILED -> {
                String detail = nav.detail() == null ? "" : nav.detail().toLowerCase(Locale.ROOT);
                yield detail.contains("timeout") ? new Outcome(Verdict.INCONCLUSIVE, FailureClass.TIMEOUT, "the page did not load in time")
                        : new Outcome(Verdict.INCONCLUSIVE, FailureClass.ENVIRONMENT_FAILURE, "the page could not be reached");
            }
            case BLOCKED -> new Outcome(Verdict.BLOCKED, FailureClass.POLICY_BLOCKED, "the browser's guard refused the page");
            case REDIRECTED -> {
                String target = nav.redirectTarget();
                if (isLogin(target)) yield new Outcome(Verdict.INCONCLUSIVE, FailureClass.AUTHENTICATION_FAILURE, "the page sent the run to sign in");
                if (hops >= MAX_REDIRECTS) yield new Outcome(Verdict.FAIL, FailureClass.APPLICATION_DEFECT, "more than " + MAX_REDIRECTS + " redirects");
                // A redirect is followed only through the gate, like any other page.
                yield open(target, hops + 1);
            }
            case LOADED -> {
                if (nav.status() >= 500) yield new Outcome(Verdict.FAIL, FailureClass.APPLICATION_DEFECT, "the server answered " + nav.status());
                if (nav.status() >= 400) yield new Outcome(Verdict.FAIL, FailureClass.APPLICATION_DEFECT, "the page answered " + nav.status());
                if (isLogin(browser.currentUrl())) yield new Outcome(Verdict.INCONCLUSIVE, FailureClass.AUTHENTICATION_FAILURE, "the page sent the run to sign in");
                yield Outcome.pass("loaded with " + nav.status());
            }
        };
    }

    private boolean isLogin(String url) {
        if (loginPath == null || url == null) return false;
        try {
            String path = URI.create(url).getPath();
            return loginPath.equals(path);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A link is followed as a link; a tab, menu item, summary or expander is used in place. Anything else is refused as what it is. */
    private Outcome activate(LocatorSpec target) {
        if (!browser.waitVisible(target, wait)) return new Outcome(Verdict.INCONCLUSIVE, FailureClass.AUTOMATION_FAILURE, target.describe() + " was not found");
        Map<String, String> el = browser.inspect(target);
        String tag = el.getOrDefault("tag", "");
        String role = el.getOrDefault("role", "");
        String href = el.get("href");
        if (tag.equals("a") && href != null && !href.isBlank()) {
            URI link = URI.create(href);
            boolean samePageFragment = link.getFragment() != null && sameDocument(link);
            if (!samePageFragment) return open(href, 0);
        }
        boolean disclosure = DISCLOSURE_ROLES.contains(role) || tag.equals("summary") || el.containsKey("aria-expanded")
                || (tag.equals("a") && href != null && URI.create(href).getFragment() != null);
        ActionRequest.Kind kind = disclosure ? ActionRequest.Kind.DISCLOSURE : kindOf(tag, role, el);
        Decision d = gate.decideAction(new ActionRequest(kind, null, attributes(el)));
        if (!d.allowed()) return blocked(d);
        browser.drainSignals();
        browser.click(target);
        if (isLogin(browser.currentUrl())) return new Outcome(Verdict.INCONCLUSIVE, FailureClass.AUTHENTICATION_FAILURE, "using it sent the run to sign in");
        return Outcome.pass("used " + kind.name().toLowerCase(Locale.ROOT));
    }

    private boolean sameDocument(URI link) {
        try {
            URI here = URI.create(browser.currentUrl());
            return java.util.Objects.equals(here.getHost(), link.getHost()) && java.util.Objects.equals(here.getPath(), link.getPath());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** What the gate is asked about, when the element is not a link or a disclosure. It refuses all of these outside sign-in. */
    private static ActionRequest.Kind kindOf(String tag, String role, Map<String, String> el) {
        String type = el.getOrDefault("type", "");
        boolean inForm = "true".equals(el.get("inForm"));
        if (tag.equals("input") && type.equals("file")) return ActionRequest.Kind.UPLOAD;
        if (tag.equals("button") || role.equals("button")) return inForm && !type.equals("button") ? ActionRequest.Kind.FORM_SUBMIT : ActionRequest.Kind.BUTTON;
        if (tag.equals("input") && (type.equals("submit") || type.equals("image"))) return ActionRequest.Kind.FORM_SUBMIT;
        return ActionRequest.Kind.UNKNOWN;
    }

    /** The element's own words go to the gate, so its risk rules see "Delete" whatever the script called the element. */
    private static Map<String, String> attributes(Map<String, String> el) {
        Map<String, String> a = new java.util.LinkedHashMap<>();
        for (String k : List.of("aria-label", "title", "id", "name", "data-testid", "type", "text")) {
            String v = el.get(k);
            if (v != null && !v.isBlank()) a.put(k, v.length() > 200 ? v.substring(0, 200) : v);
        }
        return a;
    }

    private Outcome fill(LocatorSpec target, String value) {
        if (!browser.waitVisible(target, wait)) return new Outcome(Verdict.INCONCLUSIVE, FailureClass.AUTOMATION_FAILURE, target.describe() + " was not found");
        Map<String, String> el = browser.inspect(target);
        String type = el.getOrDefault("type", "").toLowerCase(Locale.ROOT);
        String tag = el.getOrDefault("tag", "");
        if (!Set.of("input", "textarea", "select").contains(tag)) return new Outcome(Verdict.BLOCKED, FailureClass.POLICY_BLOCKED, "only fields are typed into");
        if (NEVER_FILLED.contains(type)) return new Outcome(Verdict.BLOCKED, FailureClass.POLICY_BLOCKED, "a " + type + " field is never filled by a test case");
        Decision d = gate.decideAction(new ActionRequest(ActionRequest.Kind.FORM_FILL, null, attributes(el)));
        if (!d.allowed()) return blocked(d);
        browser.fill(target, value);
        return Outcome.pass("typed " + value.length() + " characters");
    }

    private Outcome expectUrl(String expected) {
        String current = browser.currentUrl();
        URI now = URI.create(current);
        URI want = base.resolve(expected);
        boolean samePath = java.util.Objects.equals(now.getPath(), want.getPath());
        boolean sameQuery = want.getRawQuery() == null || want.getRawQuery().equals(now.getRawQuery());
        String shown = now.getRawPath() + (now.getRawQuery() == null ? "" : "?" + now.getRawQuery());
        return samePath && sameQuery ? Outcome.pass("at " + shown) : unexpected("the browser is at " + shown);
    }

    private Outcome expectText(LocatorSpec target, String expected) {
        if (target != null && !browser.waitVisible(target, wait)) return unexpected(target.describe() + " did not appear within " + wait.toSeconds() + "s");
        String text = browser.textOf(target);
        if (text == null) return unexpected("nothing to read");
        String normal = text.replaceAll("\\s+", " ");
        return normal.contains(expected) ? Outcome.pass("contains the text") : unexpected("the text \"" + expected + "\" is not there");
    }

    private Outcome expectCount(LocatorSpec target, int min) {
        // Waits for the first one, so a list still loading is not mistaken for an empty one.
        browser.waitVisible(target, wait);
        int n = browser.count(target);
        return n >= min ? Outcome.pass(n + " found") : unexpected("only " + n + " found");
    }

    private Outcome expectState(LocatorSpec target, String state) {
        // "Required" is markup, true whether or not the field is on screen: a field in a closed dialog is still required.
        boolean markup = state.equals("required");
        if (!markup && !browser.waitVisible(target, wait)) return unexpected(target.describe() + " did not appear within " + wait.toSeconds() + "s");
        Map<String, String> el = browser.inspect(target);
        if (markup && el.isEmpty()) return unexpected(target.describe() + " is not on the page");
        String attribute = "aria-" + state;
        boolean on = "true".equals(el.get(attribute)) || (state.equals("expanded") && "true".equals(el.get("open")))
                || (state.equals("required") && el.containsKey("required"));
        return on ? Outcome.pass("is " + state) : unexpected("is not " + state);
    }
}
