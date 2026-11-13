package qaengine.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.policy.PolicyException;
import qaengine.core.script.Script;
import qaengine.core.script.ScriptStep;

/** A script as the platform stores it (jsonb, with its spacing and nulls) is read by the worker into the same steps, and checked again. */
class ScriptWireFormatTest {

    static Script read(String json) {
        return new Script(Arrays.asList(new Gson().fromJson(json, ScriptStep[].class))).validated();
    }

    @Test
    void thePlatformsFormatReadsBackAsTheSameSteps() {
        String stored = """
                [{"op": "OPEN", "path": "/clinic/patients?q=Amal", "count": null, "value": null, "target": null},
                 {"op": "ACTIVATE", "path": null, "count": null, "value": null, "target": {"name": "Amal Haddad", "value": "link", "strategy": "ROLE"}},
                 {"op": "EXPECT_MIN_COUNT", "path": null, "count": 2, "value": null, "target": {"name": null, "value": "row", "strategy": "ROLE"}}]""";
        Script s = read(stored);
        assertEquals(ScriptStep.open("/clinic/patients?q=Amal"), s.steps().get(0));
        assertEquals(ScriptStep.activate(new LocatorSpec(LocatorSpec.Strategy.ROLE, "link", "Amal Haddad")), s.steps().get(1));
        assertEquals(ScriptStep.expectMinCount(new LocatorSpec(LocatorSpec.Strategy.ROLE, "row", null), 2), s.steps().get(2));
    }

    @Test
    void whatTheWorkerReceivesIsCheckedBeforeItRuns() {
        // A locator's own checks run as Gson builds the record, so an unsupported role never becomes a step.
        assertThrows(RuntimeException.class, () -> read("[{\"op\":\"OPEN\",\"path\":\"/\"},{\"op\":\"EXPECT_VISIBLE\",\"target\":{\"strategy\":\"ROLE\",\"value\":\"banana\"}}]"));
        assertThrows(PolicyException.class, () -> read("[{\"op\":\"OPEN\",\"path\":\"https://elsewhere.test/\"},{\"op\":\"EXPECT_TITLE\",\"value\":\"x\"}]"));
        assertThrows(PolicyException.class, () -> read("[{\"op\":\"OPEN\",\"path\":\"/\"}]"));
    }
}
