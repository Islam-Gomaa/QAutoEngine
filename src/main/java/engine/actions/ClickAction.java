package engine.actions;

import engine.context.ContextEngine;
import engine.goal.GoalEngine;
import engine.learning.BehaviorGraph;
import engine.learning.BehaviorStore;
import engine.state.SessionState;
import org.openqa.selenium.*;
import utilities.Waits;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public class ClickAction implements Action {

    private static final int MAX_ATTEMPTS = 10;

    @Override
    public void execute(WebDriver driver) {

        System.out.println("🖱️ [ClickAction AI] Start");

        String baseDomain = getDomain(driver.getCurrentUrl());

        // 🧠 Context + Goal
        ContextEngine.ContextType context = ContextEngine.detect(driver);
        GoalEngine.GoalType goal = GoalEngine.getGoal();

        // 🔍 Collect clickable elements
        List<WebElement> elements = findClickableElements(driver);

        // إزالة التكرار
        elements = elements.stream().distinct().collect(Collectors.toList());

        // 🔥 ترتيب بالعقل (score)
        elements.sort(Comparator.comparingDouble(el -> -score(el, context, goal)));

        int attempts = 0;

        for (WebElement el : elements) {

            if (attempts++ >= MAX_ATTEMPTS) break;

            try {

                if (!isValid(el)) continue;
                if (!isSafe(el)) continue;

                String key = buildKey(el);

                // 🔁 skip لو اتجرب قبل كده
                if (SessionState.isElementVisited(key)) continue;

                // 🌐 skip external links
                if (isExternal(el, baseDomain)) continue;

                scrollIntoView(driver, el);

                String before = driver.getCurrentUrl();

                safeClick(driver, el);

                Waits.waitForPageLoad(driver);

                String after = driver.getCurrentUrl();

                // 🧠 Learning
                BehaviorGraph.record(before, "ClickAction", after);
                BehaviorStore.recordSuccess("ClickAction");

                SessionState.markElementVisited(key);

                System.out.println("✅ Clicked: " + key);

                return; // 🔥 click واحد ذكي
            } catch (Exception e) {

                BehaviorStore.recordFailure("ClickAction");
                System.out.println("❌ Click failed → trying next");
            }
        }

        System.out.println("⚠️ No valid click found");
    }

    // ===================================================
    // 🔍 FIND ELEMENTS
    // ===================================================
    private List<WebElement> findClickableElements(WebDriver driver) {

        List<WebElement> elements = new ArrayList<>();

        elements.addAll(driver.findElements(By.tagName("button")));
        elements.addAll(driver.findElements(By.cssSelector("a[href]")));
        elements.addAll(driver.findElements(By.cssSelector("[role='button']")));
        elements.addAll(driver.findElements(By.cssSelector("input[type='button'], input[type='submit']")));
        elements.addAll(driver.findElements(By.xpath("//*[@onclick]")));

        return elements;
    }

    // ===================================================
    // 🧠 SCORE (العقل الحقيقي)
    // ===================================================
    private double score(WebElement el,
                         ContextEngine.ContextType context,
                         GoalEngine.GoalType goal) {

        double score = 0;

        String text = safeText(el);

        // 🎯 Goal
        if (goal != null) {
            switch (goal) {
                case ADD_TO_CART:
                    if (text.contains("add")) score += 10;
                    break;
                case REACH_CHECKOUT:
                    if (text.contains("checkout")) score += 10;
                    break;
                case COMPLETE_PAYMENT:
                    if (text.contains("pay")) score += 10;
                    break;
            }
        }

        // 🧠 Context
        if (context == ContextEngine.ContextType.PRODUCT && text.contains("add")) score += 5;
        if (context == ContextEngine.ContextType.CART && text.contains("checkout")) score += 5;
        if (context == ContextEngine.ContextType.CHECKOUT && text.contains("pay")) score += 5;

        // 📊 Learning
        score += BehaviorStore.getScore("ClickAction") * 5;

        // 🔑 Keywords
        if (text.contains("next")) score += 2;
        if (text.contains("continue")) score += 2;

        if (text.contains("cancel") || text.contains("close")) score -= 5;

        return score;
    }

    // ===================================================
    // 🔒 VALIDATION
    // ===================================================
    private boolean isValid(WebElement el) {
        try {
            return el.isDisplayed() && el.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSafe(WebElement el) {

        String text = safeText(el);

        return !(text.contains("logout") ||
                text.contains("delete") ||
                text.contains("remove") ||
                text.contains("sign out"));
    }

    private boolean isExternal(WebElement el, String baseDomain) {

        try {
            String href = el.getAttribute("href");
            if (href == null) return false;

            return !getDomain(href).contains(baseDomain);
        } catch (Exception e) {
            return false;
        }
    }

    // ===================================================
    // ⚙️ CLICK SAFE
    // ===================================================
    private void safeClick(WebDriver driver, WebElement el) {

        try {
            Waits.waitForClickable(driver, el).click();
            handleAlert(driver);
        } catch (Exception e) {
            jsClick(driver, el);
            handleAlert(driver);
        }
    }

    private void handleAlert(WebDriver driver) {
        try {
            Alert alert = driver.switchTo().alert();
            alert.accept();
        } catch (Exception ignored) {}
    }

    private void scrollIntoView(WebDriver driver, WebElement el) {
        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView({block:'center'});", el);
    }

    private void jsClick(WebDriver driver, WebElement el) {
        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].click();", el);
    }

    // ===================================================
    // 🧠 HELPERS
    // ===================================================
    private String safeText(WebElement el) {
        try {
            return el.getText().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildKey(WebElement el) {
        try {
            return el.getTagName() + "|" + el.getText();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
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