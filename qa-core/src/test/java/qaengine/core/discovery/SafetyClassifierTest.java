package qaengine.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class SafetyClassifierTest {

    static PageInventory.Action action(String kind, String name, String href, Map<String, String> attributes) {
        return new PageInventory.Action(kind, "", name, href, Map.of(), attributes);
    }

    @ParameterizedTest(name = "{0} \"{1}\" -> {3}")
    @CsvSource(delimiter = '|', value = {
            // The brief's own examples
            "LINK       | Orders          | https://shop.example/orders            | READ_ONLY",
            "DISCLOSURE | Filters         |                                        | READ_ONLY",
            "SUBMIT     | Save draft      |                                        | REVERSIBLE",
            "BUTTON     | Invite user     |                                        | SENSITIVE",
            "SUBMIT     | Pay invoice     |                                        | SENSITIVE",
            "BUTTON     | Delete record   |                                        | DESTRUCTIVE",
            // Links whose address does the damage are judged by it
            "LINK       | Remove          | https://shop.example/users/2/delete    | DESTRUCTIVE",
            "LINK       | Sign out        | https://shop.example/logout            | SENSITIVE",
            "LINK       | Edit            | https://shop.example/users/1/edit      | READ_ONLY",
            "LINK       | Report          | https://shop.example/r/a8Fk29dLqP03xZ7mN4tBv61Yw0Qs | SENSITIVE",
            // Words a person would use
            "BUTTON     | Archive project |                                        | DESTRUCTIVE",
            "BUTTON     | Send reminder   |                                        | SENSITIVE",
            "BUTTON     | Show details    |                                        | READ_ONLY",
            "BUTTON     | Close           |                                        | READ_ONLY",
            "BUTTON     | Rename          |                                        | REVERSIBLE",
            "BUTTON     | حذف المستخدم    |                                        | DESTRUCTIVE",
            "BUTTON     | تعديل           |                                        | REVERSIBLE",
            // Unknown intent is never assumed harmless
            "BUTTON     | Go              |                                        | SENSITIVE",
            "SUBMIT     | Continue        |                                        | SENSITIVE",
    })
    void classifies(String kind, String name, String href, SafetyClass expected) {
        assertEquals(expected, SafetyClassifier.classify(action(kind, name, href, Map.of())).safety());
    }

    @Test void attributesOutrankAFriendlyName() {
        assertEquals(SafetyClass.DESTRUCTIVE, SafetyClassifier.classify(action("LINK", "Tidy up", "https://shop.example/items/1", Map.of("data-method", "delete"))).safety());
        assertEquals(SafetyClass.SENSITIVE, SafetyClassifier.classify(action("LINK", "Tidy up", "https://shop.example/items/1", Map.of("data-method", "post"))).safety());
        assertEquals(SafetyClass.SENSITIVE, SafetyClassifier.classify(action("LINK", "Report", "https://shop.example/report.pdf", Map.of("download", ""))).safety());
        assertEquals(SafetyClass.SENSITIVE, SafetyClassifier.classify(action("BUTTON", "OK", null, Map.of("data-confirm", "Sure?"))).safety());
        assertEquals(SafetyClass.DESTRUCTIVE, SafetyClassifier.classify(action("BUTTON", "OK", null, Map.of("data-testid", "delete-user"))).safety(), "test ids say what a button is for");
    }

    @Test void theReasonNamesWhatDecided() {
        var c = SafetyClassifier.classify(action("BUTTON", "Delete record", null, Map.of()));
        assertEquals("named \"delete\"", c.reason());
        assertTrue(SafetyClassifier.classify(action("BUTTON", "Go", null, Map.of())).reason().contains("not clear"));
    }
}
