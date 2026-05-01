package engine.actions;

import engine.context.ContextEngine;
import engine.goal.GoalEngine;
import engine.state.SessionState;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.Select;
import utilities.Waits;
import utilities.DebugUtil;

import java.net.URI;
import java.util.List;

public class FormAction implements Action {

    private static final int MAX_FORMS = 5;

    @Override
    public void execute(WebDriver driver) {

        System.out.println("📝 [FormAction AI] Start");

        String baseDomain = getDomain(driver.getCurrentUrl());

        ContextEngine.ContextType context = ContextEngine.detect(driver);
        GoalEngine.GoalType goal = GoalEngine.getGoal();

        List<WebElement> forms = driver.findElements(By.tagName("form"));

        if (forms.isEmpty()) {
            System.out.println("⚠️ No forms found");
            return;
        }

        // 🔥 KEEP SORTING
        forms.sort((a, b) -> Double.compare(
                scoreForm(b, context, goal),
                scoreForm(a, context, goal)
        ));

        int attempts = 0;

        for (WebElement form : forms) {

            if (attempts++ >= MAX_FORMS) break;

            try {

                String formKey = buildFormKey(form);

                if (SessionState.shouldAvoidElement(formKey)) continue;
                if (isDangerousForm(form)) continue;
                if (!isRelevantForm(form, context, goal)) continue;

                double formScore = scoreForm(form, context, goal);

                System.out.println("➡️ Trying form (score: " + formScore + ")");

                List<WebElement> fields =
                        form.findElements(By.cssSelector("input, textarea, select"));

                // ================= FILL =================
                for (WebElement field : fields) {

                    if (!isValid(field)) continue;

                    fillField(driver, field, context, goal);
                }

                // ================= STATE =================
                String beforeUrl = driver.getCurrentUrl();
                int beforeDom = driver.findElements(By.xpath("//*")).size();
                String beforeTitle = driver.getTitle();

                highlightSubmit(driver, form);
                DebugUtil.slow(700);

                submitForm(driver, form, baseDomain);

                DebugUtil.slow(1200);

                String afterUrl = driver.getCurrentUrl();
                int afterDom = driver.findElements(By.xpath("//*")).size();
                String afterTitle = driver.getTitle();

                boolean changed =
                        !beforeUrl.equals(afterUrl)
                                || beforeDom != afterDom
                                || !beforeTitle.equals(afterTitle);

                if (changed) {

                    SessionState.markElementVisited(formKey);

                    System.out.println("✅ Form SUCCESS → score: " + formScore);
                    return;
                }

            } catch (Exception e) {

                System.out.println("❌ Form failed → trying next");
            }
        }

        System.out.println("⚠️ No valid form executed");
    }

    // ===================================================
    private double scoreForm(WebElement form,
                             ContextEngine.ContextType context,
                             GoalEngine.GoalType goal) {

        double score = 0;

        try {

            String text = form.getText().toLowerCase();

            if (goal != null) {
                switch (goal) {
                    case COMPLETE_PAYMENT: score += 15; break;
                    case REACH_CHECKOUT: score += 10; break;
                }
            }

            if (context == ContextEngine.ContextType.CHECKOUT) score += 10;

            if (text.contains("checkout")) score += 12;
            if (text.contains("payment")) score += 15;
            if (text.contains("address")) score += 8;

            if (text.contains("search")) score -= 10;
            if (text.contains("login")) score -= 8;

            return score + Math.random();

        } catch (Exception e) {
            return 0;
        }
    }

    // ===================================================
    private void fillField(WebDriver driver,
                           WebElement el,
                           ContextEngine.ContextType context,
                           GoalEngine.GoalType goal) {

        try {

            String tag = el.getTagName().toLowerCase();
            String type = get(el, "type");

            DebugUtil.highlight(driver, el);
            DebugUtil.slow(300);

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

                for (char c : value.toCharArray()) {
                    el.sendKeys(String.valueOf(c));
                    DebugUtil.slow(40);
                }
            }

        } catch (Exception ignored) {}
    }

    // ===================================================
    private void submitForm(WebDriver driver,
                            WebElement form,
                            String baseDomain) {

        try {

            WebElement submit = form.findElement(
                    By.cssSelector("button[type='submit'], input[type='submit']")
            );

            Waits.waitForClickable(driver, submit).click();
            Waits.waitForPageLoad(driver);

            String currentDomain = getDomain(driver.getCurrentUrl());

            if (!currentDomain.contains(baseDomain)) {
                driver.navigate().back();
                Waits.waitForPageLoad(driver);
            }

        } catch (Exception ignored) {}
    }

    // ===================================================
    private boolean isRelevantForm(WebElement form,
                                   ContextEngine.ContextType context,
                                   GoalEngine.GoalType goal) {

        String text = form.getText().toLowerCase();

        if (context == ContextEngine.ContextType.CHECKOUT) return true;
        if (text.contains("search")) return false;
        if (text.contains("login") && goal != GoalEngine.GoalType.EXPLORE) return false;

        return true;
    }

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
    private String generateValue(String type,
                                 String name,
                                 String placeholder,
                                 ContextEngine.ContextType context,
                                 GoalEngine.GoalType goal) {

        String key = (type + name + placeholder).toLowerCase();

        if (context == ContextEngine.ContextType.CHECKOUT) {
            if (key.contains("address")) return "Cairo Egypt";
            if (key.contains("city")) return "Cairo";
            if (key.contains("zip")) return "12345";
        }

        if (key.contains("email")) return "test@example.com";
        if (key.contains("password")) return "Test@12345";
        if (key.contains("phone")) return "01000000000";
        if (key.contains("name")) return "Automation User";
        if (key.contains("search")) return "automation";

        return "Test123";
    }

    // ===================================================
    private void highlightSubmit(WebDriver driver, WebElement form) {
        try {
            WebElement submit = form.findElement(
                    By.cssSelector("button[type='submit'], input[type='submit']")
            );
            DebugUtil.highlight(driver, submit);
        } catch (Exception ignored) {}
    }

    private void scroll(WebDriver driver, WebElement el) {
        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView({block:'center'});", el);
    }

    private String get(WebElement el, String attr) {
        String v = el.getAttribute(attr);
        return v == null ? "" : v;
    }

    private String buildFormKey(WebElement form) {
        return "form|" + form.getText();
    }

    private String getDomain(String url) {
        try { return new URI(url).getHost(); }
        catch (Exception e) { return ""; }
    }
}