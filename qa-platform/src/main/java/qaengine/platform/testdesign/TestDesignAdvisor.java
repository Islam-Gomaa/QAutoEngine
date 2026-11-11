package qaengine.platform.testdesign;

import java.util.List;

/**
 * Turns what discovery found into candidate test cases for a person to review. This is the seam a model-based
 * advisor would plug into; none is configured, and the only implementation is {@link RuleBasedAdvisor}. Whatever
 * suggests the cases, nothing here executes anything: candidates are saved for review and never run on their own.
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
    /** One discovered page, by its path (and query) on the target. Every text came from the target. */
    record Page(String path, String title, List<String> headings, List<Form> forms, List<Table> tables, List<Action> actions, List<Link> links) {}
    record Discovery(boolean signedIn, List<Page> pages) {}

    /**
     * @param key stable across runs for the same thing found, so a later discovery never duplicates a reviewed case
     * @param rule which rule suggested it
     */
    record Candidate(String key, String rule, String title, String description, String module, String priority, List<String> tags, List<Step> steps,
                     List<String> observed, List<String> inferred, List<String> unknown) {}

    String name();

    List<Candidate> suggest(Discovery discovery);
}
