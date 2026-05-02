package engine.actions;

import engine.intelligence.ProgressEngine;
import engine.intelligence.ScenarioTracker;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.Select;
import utilities.Waits;

import java.util.*;

public class FormAction implements Action {

    private static final int MAX_RETRIES = 3;

    @Override
    public void execute(WebDriver driver) {

        System.out.println("🧠 [FormAction AI ULTRA]");

        List<WebElement> forms = driver.findElements(By.tagName("form"));

        for (WebElement form : forms) {

            if (!form.isDisplayed()) continue;

            String lastError = null;

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {

                try {

                    String beforeUrl = driver.getCurrentUrl();
                    int beforeDom = driver.findElements(By.xpath("//*")).size();

                    fillForm(driver, form, lastError);

                    if (!submitSmart(form)) continue;

                    Waits.waitForPageLoad(driver);

                    String afterUrl = driver.getCurrentUrl();
                    int afterDom = driver.findElements(By.xpath("//*")).size();

                    double progress = ProgressEngine.evaluateProgress(
                            driver, beforeUrl, afterUrl, beforeDom, afterDom
                    );

                    if (progress > 2) {

                        ScenarioTracker.record("FormAction", afterUrl, progress);

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

                String fieldType = detectFieldType(el);
                Map<String, Object> constraints = extractConstraints(el);

                String value = generateValue(fieldType, constraints, lastError);

                el.clear();
                el.sendKeys(value);

            } catch (Exception ignored) {}
        }
    }

    // ===================================================
    // 🧠 FIELD DETECTION (قوي جدًا)
    // ===================================================
    private String detectFieldType(WebElement el) {

        String key = (
                safe(el.getAttribute("type")) + " " +
                        safe(el.getAttribute("name")) + " " +
                        safe(el.getAttribute("id")) + " " +
                        safe(el.getAttribute("placeholder")) + " " +
                        safe(el.getAttribute("aria-label"))
        ).toLowerCase();

        if (key.matches(".*email.*")) return "EMAIL";
        if (key.matches(".*password.*")) return "PASSWORD";
        if (key.matches(".*phone|mobile.*")) return "PHONE";
        if (key.matches(".*name|user.*")) return "NAME";
        if (key.matches(".*address|city.*")) return "ADDRESS";
        if (key.matches(".*zip|postal.*")) return "ZIP";
        if (key.matches(".*date|birth.*")) return "DATE";

        return "TEXT";
    }

    // ===================================================
    // 🔍 CONSTRAINT EXTRACTION
    // ===================================================
    private Map<String, Object> extractConstraints(WebElement el) {

        Map<String, Object> c = new HashMap<>();

        try {

            if (el.getAttribute("required") != null)
                c.put("required", true);

            String min = el.getAttribute("minlength");
            String max = el.getAttribute("maxlength");
            String pattern = el.getAttribute("pattern");

            if (min != null) c.put("min", Integer.parseInt(min));
            if (max != null) c.put("max", Integer.parseInt(max));
            if (pattern != null) c.put("pattern", pattern);

        } catch (Exception ignored) {}

        return c;
    }

    // ===================================================
    // 💣 SMART GENERATOR (Adaptive)
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

        // 🔥 error adaptation
        if ("INVALID".equals(error)) {
            base = base + "X1!";
        }

        if ("REQ".equals(error)) {
            base = "Valid" + rand(1000);
        }

        // enforce length
        if (base.length() < min)
            base += "X".repeat(min - base.length());

        if (base.length() > max)
            base = base.substring(0, max);

        return base;
    }

    // ===================================================
    // 🚀 SMART SUBMIT (Scoring)
    // ===================================================
    private boolean submitSmart(WebElement form) {

        List<WebElement> buttons =
                form.findElements(By.cssSelector("button, input"));

        double bestScore = -999;
        WebElement best = null;

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
                if (key.contains("cancel") || key.contains("close")) score -= 10;

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
    private int rand(int max) {
        return new Random().nextInt(max);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}