package qaengine.core.script;

import qaengine.core.auth.LocatorSpec;

/**
 * One step of an executable test case. Actions go through the gate like any other interaction; expectations only read
 * the page. Locators are role, label, placeholder, test id or CSS, as everywhere else in the engine.
 *
 * @param path  OPEN only: a path on the environment's own origin, optionally with a query. Never a full URL
 * @param target the element an action uses or an expectation reads. Optional for EXPECT_TEXT, which then reads the page
 * @param value  FILL: the text to type. EXPECT_URL: the expected path. EXPECT_TITLE and EXPECT_TEXT: text that must appear.
 *               EXPECT_STATE: "selected", "expanded" or "required"
 * @param count  EXPECT_MIN_COUNT only: how many matching elements there must at least be
 */
public record ScriptStep(Op op, String path, LocatorSpec target, String value, Integer count) {

    public enum Op {
        /** Opens a page of the environment. Decided by the gate as a link. */
        OPEN,
        /** Follows a link, or uses a disclosure control in place: a tab, an accordion, a menu. Nothing else. */
        ACTIVATE,
        /** Types into a field. The gate allows it only in VALIDATION_ONLY. Nothing is ever submitted. */
        FILL,
        EXPECT_URL,
        EXPECT_TITLE,
        EXPECT_VISIBLE,
        EXPECT_TEXT,
        EXPECT_MIN_COUNT,
        /** The field's own validation refuses its current value. Read without submitting anything. */
        EXPECT_INVALID,
        EXPECT_STATE;

        public boolean isExpectation() {
            return name().startsWith("EXPECT_");
        }
    }

    public static ScriptStep open(String path) {
        return new ScriptStep(Op.OPEN, path, null, null, null);
    }

    public static ScriptStep activate(LocatorSpec target) {
        return new ScriptStep(Op.ACTIVATE, null, target, null, null);
    }

    public static ScriptStep fill(LocatorSpec target, String value) {
        return new ScriptStep(Op.FILL, null, target, value, null);
    }

    public static ScriptStep expectUrl(String path) {
        return new ScriptStep(Op.EXPECT_URL, null, null, path, null);
    }

    public static ScriptStep expectTitle(String text) {
        return new ScriptStep(Op.EXPECT_TITLE, null, null, text, null);
    }

    public static ScriptStep expectVisible(LocatorSpec target) {
        return new ScriptStep(Op.EXPECT_VISIBLE, null, target, null, null);
    }

    public static ScriptStep expectText(LocatorSpec target, String text) {
        return new ScriptStep(Op.EXPECT_TEXT, null, target, text, null);
    }

    public static ScriptStep expectMinCount(LocatorSpec target, int count) {
        return new ScriptStep(Op.EXPECT_MIN_COUNT, null, target, null, count);
    }

    public static ScriptStep expectInvalid(LocatorSpec target) {
        return new ScriptStep(Op.EXPECT_INVALID, null, target, null, null);
    }

    public static ScriptStep expectState(LocatorSpec target, String state) {
        return new ScriptStep(Op.EXPECT_STATE, null, target, state, null);
    }

    /** For step lists, evidence and reports. Never shows what FILL types, which may be test data the reviewer chose. */
    public String describe() {
        String on = target == null ? "" : target.describe();
        return switch (op) {
            case OPEN -> "Open " + path;
            case ACTIVATE -> "Use " + on;
            case FILL -> "Type into " + on;
            case EXPECT_URL -> "Expect to be at " + value;
            case EXPECT_TITLE -> "Expect the title to contain \"" + value + "\"";
            case EXPECT_VISIBLE -> "Expect " + on + " to be visible";
            case EXPECT_TEXT -> "Expect " + (target == null ? "the page" : on) + " to contain \"" + value + "\"";
            case EXPECT_MIN_COUNT -> "Expect at least " + count + " of " + on;
            case EXPECT_INVALID -> "Expect " + on + " to refuse its value";
            case EXPECT_STATE -> "Expect " + on + " to be " + value;
        };
    }
}
