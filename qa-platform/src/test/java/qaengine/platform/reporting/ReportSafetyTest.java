package qaengine.platform.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReportSafetyTest {

    @ParameterizedTest
    @ValueSource(strings = {"=HYPERLINK(\"http://evil\")", "+1+1", "-2", "@SUM(1)"})
    void formulasFromTargetsAreNeutralized(String value) {
        assertEquals("'" + value, ReportController.safe(value));
    }

    @org.junit.jupiter.api.Test
    void ordinaryTextIsUnchanged() {
        assertEquals("https://app.test/a", ReportController.safe("https://app.test/a"));
    }
}
