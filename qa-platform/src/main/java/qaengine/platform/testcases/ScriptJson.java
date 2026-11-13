package qaengine.platform.testcases;

import java.util.List;
import qaengine.core.policy.PolicyException;
import qaengine.core.script.Script;
import qaengine.core.script.ScriptStep;
import qaengine.platform.shared.ConflictException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Reads and writes executable scripts as stored on a test case version, validating on the way in and on the way out. */
public final class ScriptJson {

    public static final java.util.Set<String> MODES = java.util.Set.of("READ_ONLY", "VALIDATION_ONLY");

    private ScriptJson() {}

    /** @throws ConflictException with the script's own fixed message when it is not valid */
    public static Script parse(ObjectMapper json, String text) {
        List<ScriptStep> steps;
        try {
            steps = json.readValue(text, new TypeReference<List<ScriptStep>>() {});
        } catch (RuntimeException e) {
            throw new ConflictException("The script is not a list of steps");
        }
        return validated(steps);
    }

    public static Script validated(List<ScriptStep> steps) {
        try {
            return new Script(steps).validated();
        } catch (PolicyException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    /** The least permissive mode a script can run in: typing needs VALIDATION_ONLY. */
    public static String modeFor(Script script, String asked) {
        boolean types = script.steps().stream().anyMatch(s -> s.op() == ScriptStep.Op.FILL);
        String mode = asked == null || asked.isBlank() ? (types ? "VALIDATION_ONLY" : "READ_ONLY") : asked;
        if (!MODES.contains(mode)) throw new ConflictException("A script runs in READ_ONLY or VALIDATION_ONLY");
        if (types && mode.equals("READ_ONLY")) throw new ConflictException("A script that types into fields runs in VALIDATION_ONLY");
        return mode;
    }

    public static String write(ObjectMapper json, Script script) {
        return json.writeValueAsString(script.steps());
    }
}
