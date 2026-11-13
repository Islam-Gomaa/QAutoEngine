package ai.intelligence;

import ai.learning.State;

import java.util.*;

public class ScenarioExporter {

    // ===================================================
    // 🧠 MAIN ENTRY
    // ===================================================
    public static void exportFull() {

        List<ScenarioTracker.Step> steps = ScenarioTracker.getBestScenario();

        if (steps.isEmpty()) {
            System.out.println("❌ No scenario to export");
            return;
        }

        printReadableTestCase(steps);
        generateSeleniumTest(steps);
        generateAssertions(steps);
        printQualityReport(steps);
    }

    // ===================================================
    // 🧾 HUMAN TEST CASE (UPGRADED)
    // ===================================================
    private static void printReadableTestCase(List<ScenarioTracker.Step> steps) {

        System.out.println("\n===== 🧪 HUMAN TEST CASE =====");

        int i = 1;

        for (ScenarioTracker.Step step : steps) {

            System.out.println("Step " + i++ + ":");
            System.out.println("  Action: " + normalizeAction(step.action));
            System.out.println("  URL: " + simplifyUrl(step.url));
            System.out.println("  State: " + step.stateSignature);
            System.out.println("  Expected: Page changes or new content appears\n");
        }
    }

    // ===================================================
    // 🤖 SELENIUM TEST (SMART)
    // ===================================================
    private static void generateSeleniumTest(List<ScenarioTracker.Step> steps) {

        System.out.println("\n===== 🤖 GENERATED SELENIUM TEST =====");

        System.out.println("public void testScenario() {");

        for (ScenarioTracker.Step step : steps) {

            String code = mapToSelenium(step);

            if (code != null) {
                System.out.println("    " + code);
            }

            // 🔥 auto wait
            System.out.println("    waitForPageLoad();");
        }

        System.out.println("}");
    }

    private static String mapToSelenium(ScenarioTracker.Step step) {

        switch (step.action) {

            case "ClickAction":
                return "driver.findElement(By.xpath(\"//button | //a\")).click();";

            case "FormAction":
                return """
                       for (WebElement input : driver.findElements(By.xpath("//input | //textarea"))) {
                           input.sendKeys("test");
                       }
                       """;

            case "NavigationAction":
                return "driver.get(\"" + step.url + "\");";

            default:
                return null;
        }
    }

    // ===================================================
    // 🧠 ASSERTIONS (SMART)
    // ===================================================
    private static void generateAssertions(List<ScenarioTracker.Step> steps) {

        System.out.println("\n===== 🧠 ASSERTIONS =====");

        for (ScenarioTracker.Step step : steps) {

            if (step.progressScore > 4) {

                System.out.println("// Assert meaningful change");
                System.out.println("assertTrue(driver.getCurrentUrl().contains(\""
                        + extractKeyword(step.url) + "\"));");
            }
        }
    }

    // ===================================================
    // 📊 QUALITY REPORT (UPGRADED)
    // ===================================================
    private static void printQualityReport(List<ScenarioTracker.Step> steps) {

        double avgScore = steps.stream()
                .mapToDouble(s -> s.progressScore)
                .average()
                .orElse(0);

        long uniqueStates = steps.stream()
                .map(s -> s.stateSignature)
                .distinct()
                .count();

        System.out.println("\n===== 📊 SCENARIO QUALITY =====");
        System.out.println("Steps: " + steps.size());
        System.out.println("Unique States: " + uniqueStates);
        System.out.println("Avg Progress Score: " + avgScore);

        if (avgScore > 4) System.out.println("🔥 Strong Scenario");
        else if (avgScore > 2) System.out.println("⚠️ Medium Scenario");
        else System.out.println("❌ Weak Scenario");
    }

    // ===================================================
    // 🔥 HELPERS
    // ===================================================
    private static String normalizeAction(String action) {

        return switch (action) {
            case "ClickAction" -> "Click Element";
            case "FormAction" -> "Fill Form";
            case "NavigationAction" -> "Navigate";
            default -> action;
        };
    }

    private static String simplifyUrl(String url) {

        if (url == null) return "unknown";

        return url.replaceAll("https?://", "")
                .replaceAll("\\d+", "{id}");
    }

    private static String extractKeyword(String url) {

        if (url == null) return "";

        String[] parts = url.split("/");

        return parts.length > 2 ? parts[parts.length - 1] : "";
    }
}