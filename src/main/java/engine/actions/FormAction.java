package engine.actions;

import ai.intelligence.ProgressEngine;
import ai.intelligence.ScenarioTracker;
import ai.learning.State;
import engine.state.SessionState;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.Select;
import utilities.Waits;

import java.util.*;

public class FormAction implements Action {

    private static final int MAX_RETRIES = 3;

    @Override
    public void execute(WebDriver driver) {

        System.out.println("🧠 [FormAction AI V6]");

        List<WebElement> forms = driver.findElements(By.tagName("form"));

        for (WebElement form : forms) {

            if (!form.isDisplayed()) continue;

            String lastError = null;

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {

                try {

                    State before = buildState(driver);

                    fillForm(driver, form, lastError);

                    if (!submitSmart(form)) continue;

                    Waits.waitForPageLoad(driver);

                    State after = buildState(driver);

                    double progress = ProgressEngine.evaluateProgress(
                            driver,
                            before,
                            after,
                            before.url,
                            after.url,
                            before.domSize,
                            after.domSize
                    );

                    if (progress > 2) {

                        SessionState.markStateVisited(after);

                        ScenarioTracker.record(
                                "FormAction",
                                after.url,
                                after,
                                progress
                        );

                        System.out.println("✅ Form SUCCESS → " + progress);
                        return;
                    }

                    lastError = detectError(driver);

                    System.out.println("⚠️ Retry with error: " + lastError);

                } catch (Exception ignored) {}
            }
        }
    }

    // ===================================================
    // 💣 SMART FILL
    // ===================================================
    private void fillForm(WebDriver driver, WebElement form, String lastError) {

        List<WebElement> fields =
                form.findElements(By.cssSelector("input, textarea, select"));

        for (WebElement el : fields) {

            try {

                if (!el.isDisplayed() || !el.isEnabled()) continue;

                if ("select".equals(el.getTagName())) {
                    Select s = new Select(el);
                    if (s.getOptions().size() > 1)
                        s.selectByIndex(1);
                    continue;
                }

                String type = detectFieldType(el);
                Map<String, Object> constraints = extractConstraints(el);

                String value = generateValue(type, constraints, lastError);

                el.clear();
                el.sendKeys(value);

            } catch (Exception ignored) {}
        }
    }

    // ===================================================
    // 🧠 FIELD TYPE
    // ===================================================
    private String detectFieldType(WebElement el) {

        String key = (
                safe(el.getAttribute("type")) + " " +
                        safe(el.getAttribute("name")) + " " +
                        safe(el.getAttribute("id")) + " " +
                        safe(el.getAttribute("placeholder")) + " " +
                        safe(el.getAttribute("aria-label"))
        ).toLowerCase();

        if (key.contains("email")) return "EMAIL";
        if (key.contains("password")) return "PASSWORD";
        if (key.contains("phone") || key.contains("mobile")) return "PHONE";
        if (key.contains("name") || key.contains("user")) return "NAME";
        if (key.contains("address")) return "ADDRESS";
        if (key.contains("zip") || key.contains("postal")) return "ZIP";
        if (key.contains("date") || key.contains("birth")) return "DATE";

        return "TEXT";
    }

    // ===================================================
    // 🔍 CONSTRAINTS
    // ===================================================
    private Map<String, Object> extractConstraints(WebElement el) {

        Map<String, Object> c = new HashMap<>();

        try {

            if (el.getAttribute("required") != null)
                c.put("required", true);

            String min = el.getAttribute("minlength");
            String max = el.getAttribute("maxlength");

            if (min != null) c.put("min", Integer.parseInt(min));
            if (max != null) c.put("max", Integer.parseInt(max));

        } catch (Exception ignored) {}

        return c;
    }

    // ===================================================
    // 💣 VALUE GENERATION
    // ===================================================
    private String generateValue(String type,
                                 Map<String, Object> c,
                                 String error) {

        int min = (int) c.getOrDefault("min", 3);
        int max = (int) c.getOrDefault("max", 15);

        String base;

        switch (type) {

            case "EMAIL":
                base = "user" + rand(1000) + "@mail.com";
                break;

            case "PASSWORD":
                base = "Aa@" + rand(10000);
                break;

            case "PHONE":
                base = "010" + rand(10000000);
                break;

            case "NAME":
                base = "User" + rand(100);
                break;

            default:
                base = "Test" + rand(1000);
        }

        // 🔥 adaptive retry
        if ("INVALID".equals(error)) base += "X1!";
        if ("REQ".equals(error)) base = "Valid" + rand(1000);

        if (base.length() < min)
            base += "X".repeat(min - base.length());

        if (base.length() > max)
            base = base.substring(0, max);

        return base;
    }

    // ===================================================
    // 🚀 SUBMIT
    // ===================================================
    private boolean submitSmart(WebElement form) {

        List<WebElement> buttons =
                form.findElements(By.cssSelector("button, input"));

        WebElement best = null;
        double bestScore = -999;

        for (WebElement btn : buttons) {

            try {

                String key = (
                        safe(btn.getText()) + " " +
                                safe(btn.getAttribute("type")) + " " +
                                safe(btn.getAttribute("value")) + " " +
                                safe(btn.getAttribute("class"))
                ).toLowerCase();

                double score = 0;

                if (key.matches(".*submit|login|continue|next|pay.*")) score += 10;
                if (key.matches(".*save|confirm|finish.*")) score += 6;
                if (key.contains("cancel")) score -= 10;

                if (score > bestScore) {
                    bestScore = score;
                    best = btn;
                }

            } catch (Exception ignored) {}
        }

        try {

            if (best != null) {
                best.click();
                return true;
            }

            form.submit();
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    // ===================================================
    // 💣 ERROR DETECTION
    // ===================================================
    private String detectError(WebDriver driver) {

        String page = driver.getPageSource().toLowerCase();

        if (page.contains("required")) return "REQ";
        if (page.contains("invalid")) return "INVALID";
        if (page.contains("too short")) return "SHORT";
        if (page.contains("too long")) return "LONG";

        return null;
    }

    // ===================================================
    // 💣 STATE BUILDER
    // ===================================================
    private State buildState(WebDriver driver) {

        int domSize = driver.findElements(By.xpath("//*")).size();

        boolean hasForm = !driver.findElements(By.tagName("form")).isEmpty();
        boolean hasLinks = !driver.findElements(By.tagName("a")).isEmpty();

        return new State(
                driver.getCurrentUrl(),
                domSize,
                "FORM",
                hasForm,
                hasLinks
        );
    }

    private int rand(int max) {
        return new Random().nextInt(max);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}