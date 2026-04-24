package engine.actions;

import engine.context.ContextEngine;
import engine.goal.GoalEngine;
import engine.learning.BehaviorGraph;
import engine.learning.BehaviorStore;
import engine.state.SessionState;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.Select;
import utilities.Waits;

import java.net.URI;
import java.util.List;

public class FormAction implements Action {

    private static final int MAX_FORMS = 5;

    @Override
    public void execute(WebDriver driver) {

        System.out.println("📝 [FormAction AI] Start");

        String baseDomain = getDomain(driver.getCurrentUrl());

        // 🧠 Context + Goal
        ContextEngine.ContextType context = ContextEngine.detect(driver);
        GoalEngine.GoalType goal = GoalEngine.getGoal();

        List<WebElement> forms = driver.findElements(By.tagName("form"));

        int processed = 0;

        for (WebElement form : forms) {

            if (processed++ >= MAX_FORMS) break;

            try {

                String formKey = buildFormKey(form);

                // 🔁 متكرر؟ سيبه
                if (!SessionState.markElementVisited(formKey)) continue;

                // 🔒 فورم خطر؟ سيبه
                if (isDangerousForm(form)) continue;

                // 🧠 مش مناسب للسياق/الهدف؟ سيبه
                if (!isRelevantForm(form, context, goal)) continue;

                // 🔍 هات الحقول
                List<WebElement> fields =
                        form.findElements(By.cssSelector("input, textarea, select"));

                // ✍️ املا الحقول بذكاء
                for (WebElement field : fields) {

                    if (!isValid(field)) continue;

                    fillField(driver, field, context, goal);
                }

                // 🚀 submit لو مناسب
                if (shouldSubmit(form, context, goal)) {

                    String before = driver.getCurrentUrl();

                    submitForm(driver, form, baseDomain);

                    String after = driver.getCurrentUrl();

                    // 🧠 Learning
                    BehaviorGraph.record(before, "FormAction", after);
                    BehaviorStore.recordSuccess("FormAction");

                    System.out.println("✅ Form submitted");

                    return; // 🔥 فورم واحد ذكي
                }

            } catch (Exception e) {

                BehaviorStore.recordFailure("FormAction");
                System.out.println("❌ Form failed → trying next");
            }
        }

        System.out.println("⚠️ No valid form executed");
    }

    // ===================================================
    // 🧠 FILL FIELD (SMART)
    // ===================================================
    private void fillField(WebDriver driver,
                           WebElement el,
                           ContextEngine.ContextType context,
                           GoalEngine.GoalType goal) {

        try {

            String tag = el.getTagName().toLowerCase();
            String type = get(el, "type");

            if ("select".equals(tag)) {

                Select select = new Select(el);
                if (select.getOptions().size() > 1) {
                    select.selectByIndex(1);
                }

            } else if ("checkbox".equals(type) || "radio".equals(type)) {

                if (!el.isSelected()) {
                    scroll(driver, el);
                    el.click();
                }

            } else {

                String value = generateValue(
                        type,
                        get(el, "name"),
                        get(el, "placeholder"),
                        context,
                        goal
                );

                scroll(driver, el);

                el.clear();
                el.sendKeys(value);
            }

        } catch (Exception ignored) {}
    }

    // ===================================================
    // 🔥 SMART DATA GENERATION
    // ===================================================
    private String generateValue(String type,
                                 String name,
                                 String placeholder,
                                 ContextEngine.ContextType context,
                                 GoalEngine.GoalType goal) {

        String key = (type + name + placeholder).toLowerCase();

        // 🎯 context-aware
        if (context == ContextEngine.ContextType.CHECKOUT) {

            if (key.contains("address")) return "Cairo Egypt";
            if (key.contains("city")) return "Cairo";
            if (key.contains("zip")) return "12345";
        }

        // 🔑 smart defaults
        if (key.contains("email")) return "test@example.com";
        if (key.contains("password")) return "Test@12345";
        if (key.contains("phone")) return "01000000000";
        if (key.contains("name")) return "Automation User";
        if (key.contains("search")) return "automation";

        return "Test123";
    }

    // ===================================================
    // 🧠 FORM FILTER
    // ===================================================
    private boolean isRelevantForm(WebElement form,
                                   ContextEngine.ContextType context,
                                   GoalEngine.GoalType goal) {

        String text = form.getText().toLowerCase();

        if (context == ContextEngine.ContextType.CHECKOUT) return true;

        if (text.contains("search")) return false;

        if (text.contains("login") && goal != GoalEngine.GoalType.EXPLORE) {
            return false;
        }

        return true;
    }

    // ===================================================
    private boolean shouldSubmit(WebElement form,
                                 ContextEngine.ContextType context,
                                 GoalEngine.GoalType goal) {

        String text = form.getText().toLowerCase();

        if (text.contains("delete")) return false;

        if (goal == GoalEngine.GoalType.COMPLETE_PAYMENT) return true;

        return !text.contains("login");
    }

    // ===================================================
    // 🚀 SUBMIT
    // ===================================================
    private void submitForm(WebDriver driver, WebElement form, String baseDomain) {

        try {

            WebElement submit = form.findElement(
                    By.cssSelector("button[type='submit'], input[type='submit']")
            );

            Waits.waitForClickable(driver, submit).click();
            Waits.waitForPageLoad(driver);

            // 🔒 لو خرج من الدومين ارجع
            String currentDomain = getDomain(driver.getCurrentUrl());

            if (!currentDomain.contains(baseDomain)) {
                driver.navigate().back();
                Waits.waitForPageLoad(driver);
            }

        } catch (Exception ignored) {}
    }

    // ===================================================
    // 🔒 VALIDATION
    // ===================================================
    private boolean isDangerousForm(WebElement form) {

        String text = form.getText().toLowerCase();

        return text.contains("delete") ||
                text.contains("remove") ||
                text.contains("reset");
    }

    private boolean isValid(WebElement el) {
        try {
            return el.isDisplayed() && el.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    // ===================================================
    // 🧠 HELPERS
    // ===================================================
    private String get(WebElement el, String attr) {
        String v = el.getAttribute(attr);
        return v == null ? "" : v;
    }

    private void scroll(WebDriver driver, WebElement el) {
        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView({block:'center'});", el);
    }

    private String buildFormKey(WebElement form) {
        try {
            return "form|" + form.getText();
        } catch (Exception e) {
            return "form|" + System.currentTimeMillis();
        }
    }

    private String getDomain(String url) {
        try {
            return new URI(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }
}