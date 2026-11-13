package qaengine.platform.testdesign;

import java.util.List;
import qaengine.core.script.Script;

/**
 * Turns what discovery found into candidate test cases for a person to review. {@link RuleBasedAdvisor} always runs;
 * {@link AiTestDesignAdvisor} adds cases from a model when one is configured. Whatever suggests the cases, nothing here
 * executes anything: candidates are saved for review, and only a case a person approved is ever run.
 */
public interface TestDesignAdvisor {

    /** Where an expected result comes from. Only OBSERVED was seen; INFERRED follows from the page; UNKNOWN must be confirmed. */
    enum Basis { OBSERVED, INFERRED, UNKNOWN }

    record Step(String action, String expected, Basis basis) {}

    record Field(String label, String name, String type, boolean required, boolean labelled) {}
    record Form(String name, String method, String action, List<Field> fields, String submitLabel) {}
    record Table(String caption, List<String> headers, int rows) {}
    record Action(String kind, String name, String href, String safety) {}
    record Link(String toPath, String toTitle, String via) {}
    /**
     * One discovered page state, by its path (and query) on the target. Every text came from the target.
     *
     * @param onLoad this is the state the page is in when it opens, rather than one reached by using something on it.
     *               An executable case opens the page, so only these states can be turned into one
     */
    record Page(String path, String title, List<String> headings, List<Form> forms, List<Table> tables, List<Action> actions, List<Link> links, boolean onLoad) {
        public Page(String path, String title, List<String> headings, List<Form> forms, List<Table> tables, List<Action> actions, List<Link> links) {
            this(path, title, headings, forms, tables, actions, links, true);
        }
    }
    record Discovery(boolean signedIn, List<Page> pages) {}

    /**
     * @param key stable across runs for the same thing found, so a later discovery never duplicates a reviewed case
     * @param rule which rule suggested it
     * @param script the steps a worker can execute to check exactly what the title says, or null when that needs a
     *               person: an expected result not observed, or an action no policy allows
     * @param executionMode the least permissive mode the script can run in: READ_ONLY, or VALIDATION_ONLY when it types
     * @param risk what using the case's subject could do: LOW for reading, MEDIUM for forms, HIGH for sensitive or destructive actions
     * @param confidence HIGH when every expected result was observed, MEDIUM when one is inferred, LOW when one is unknown
     */
    record Candidate(String key, String rule, String title, String description, String module, String priority, List<String> tags, List<Step> steps,
                     List<String> observed, List<String> inferred, List<String> unknown, Script script, String executionMode, String risk, String confidence) {
        public Candidate(String key, String rule, String title, String description, String module, String priority, List<String> tags, List<Step> steps,
                         List<String> observed, List<String> inferred, List<String> unknown) {
            this(key, rule, title, description, module, priority, tags, steps, observed, inferred, unknown, null, null, "LOW", confidenceOf(steps));
        }
    }

    static String confidenceOf(List<Step> steps) {
        if (steps.stream().anyMatch(s -> s.basis() == Basis.UNKNOWN)) return "LOW";
        if (steps.stream().anyMatch(s -> s.basis() == Basis.INFERRED)) return "MEDIUM";
        return "HIGH";
    }

    String name();

    List<Candidate> suggest(Discovery discovery);
}
