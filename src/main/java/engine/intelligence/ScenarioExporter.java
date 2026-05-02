package engine.intelligence;

import java.util.*;
import java.util.stream.Collectors;

public class ScenarioExporter {

    // ===============================
    // 🧠 MAIN ENTRY
    // ===============================
    public static void exportFull() {

        List<ScenarioTracker.Step> steps = ScenarioTracker.getBestPath();

        if (steps.isEmpty()) {
            System.out.println("❌ No scenario to export");
            return;
        }

        printReadableTestCase(steps);
        generateSeleniumTest(steps);
        generateAssertions(steps);
        printQualityReport(steps);
    }

    // ===============================
    // 🧾 HUMAN TEST CASE
    // ===============================
    private static void printReadableTestCase(List<ScenarioTracker.Step> steps) {

        System.out.println("\n===== 🧪 HUMAN TEST CASE =====");

        int i = 1;

        for (ScenarioTracker.Step step : steps) {

            System.out.println("Step " + i++ + ":");
            System.out.println("  Action: " + normalizeAction(step.action));
            System.out.println("  Target: " + simplifyUrl(step.url));
            System.out.println("  Expected: Page loads correctly\n");
        }
    }

    // ===============================
    // 🤖 SELENIUM TEST GENERATOR
    // ===============================
    private static void generateSeleniumTest(List<ScenarioTracker.Step> steps) {

        System.out.println("\n===== 🤖 GENERATED SELENIUM TEST =====");

        System.out.println("public void testScenario() {");

        for (ScenarioTracker.Step step : steps) {

            String code = mapToSelenium(step);

            if (code != null) {
                System.out.println("    " + code);
            }
        }

        System.out.println("}");
    }

    private static String mapToSelenium(ScenarioTracker.Step step) {

        switch (step.action) {

            case "ClickAction":
                return "driver.findElement(By.cssSelector(\"" + guessSelector(step) + "\")).click();";

            case "FormAction":
                return "driver.findElement(By.cssSelector(\"input\")).sendKeys(\"test-data\");";

            case "NavigationAction":
                return "driver.get(\"" + step.url + "\");";

            default:
                return null;
        }
    }

    // ===============================
    // 🧠 ASSERTIONS GENERATOR
    // ===============================
    private static void generateAssertions(List<ScenarioTracker.Step> steps) {

        System.out.println("\n===== 🧠 ASSERTIONS =====");

        for (ScenarioTracker.Step step : steps) {

            if (isTerminal(step.url)) {

                System.out.println("assertTrue(driver.getPageSource().contains(\"success\"));");
            }
        }
    }

    // ===============================
    // 📊 QUALITY REPORT
    // ===============================
    private static void printQualityReport(List<ScenarioTracker.Step> steps) {

        double avgScore = steps.stream()
                .mapToDouble(s -> s.progressScore)
                .average()
                .orElse(0);

        long uniqueUrls = steps.stream()
                .map(s -> s.url)
                .distinct()
                .count();

        System.out.println("\n===== 📊 SCENARIO QUALITY =====");
        System.out.println("Steps: " + steps.size());
        System.out.println("Unique Pages: " + uniqueUrls);
        System.out.println("Avg Progress Score: " + avgScore);

        if (avgScore > 3) System.out.println("🔥 Strong Scenario");
        else System.out.println("⚠️ Weak Scenario");
    }

    // ===============================
    // 🔍 SMART HELPERS
    // ===============================
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

    private static boolean isTerminal(String url) {

        if (url == null) return false;

        url = url.toLowerCase();

        return url.contains("success")
                || url.contains("complete")
                || url.contains("done");
    }

    private static String guessSelector(ScenarioTracker.Step step) {

        // 🔥 AI heuristic selector
        if (step.url.contains("login")) return "button[type='submit']";
        if (step.url.contains("checkout")) return ".checkout-btn";

        return "button";
    }
}