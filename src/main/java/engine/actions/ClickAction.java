package engine.actions;

import engine.context.ContextEngine;
import engine.goal.GoalEngine;
import engine.state.SessionState;
import org.openqa.selenium.*;
import utilities.Waits;
import utilities.DebugUtil;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public class ClickAction implements Action {

    private static final int MAX_ATTEMPTS = 10;

    @Override
    public void execute(WebDriver driver) {

        System.out.println("🖱️ [ClickAction AI] Start");

        String baseDomain = getDomain(driver.getCurrentUrl());

        ContextEngine.ContextType context = ContextEngine.detect(driver);
        GoalEngine.GoalType goal = GoalEngine.getGoal();

        List<WebElement> elements = findClickableElements(driver);

        if (elements.isEmpty()) {
            System.out.println("⚠️ No clickable elements found");
            return;
        }

        // 🔥 KEEP POWER (filter + sort)
        elements = elements.stream()
                .filter(this::isValid)
                .distinct()
                .sorted((a, b) -> Double.compare(
                        score(b, context, goal),
                        score(a, context, goal)
                ))
                .collect(Collectors.toList());

        int attempts = 0;

        for (WebElement el : elements) {

            if (attempts++ >= MAX_ATTEMPTS) break;

            try {

                if (!isSafe(el)) continue;
                if (isExternal(el, baseDomain)) continue;

                String key = buildKey(el);

                if (SessionState.shouldAvoidElement(key)) continue;

                double elementScore = score(el, context, goal);

                System.out.println("➡️ Trying element (score: " + elementScore + ")");

                scrollIntoView(driver, el);
                DebugUtil.highlight(driver, el);
                DebugUtil.slow(300);

                String beforeUrl = driver.getCurrentUrl();
                int beforeDom = driver.findElements(By.xpath("//*")).size();
                String beforeTitle = driver.getTitle();

                safeClick(driver, el);

                DebugUtil.slow(800);

                String afterUrl = driver.getCurrentUrl();
                int afterDom = driver.findElements(By.xpath("//*")).size();
                String afterTitle = driver.getTitle();

                boolean changed =
                        !beforeUrl.equals(afterUrl)
                                || beforeDom != afterDom
                                || !beforeTitle.equals(afterTitle);

                if (changed) {

                    SessionState.markElementVisited(key);

                    System.out.println("✅ Click SUCCESS → score: " + elementScore);
                    return;
                }

            } catch (Exception e) {

                System.out.println("❌ Click failed → trying next");
            }
        }

        System.out.println("⚠️ ClickAction finished without change");
    }

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
    private double score(WebElement el,
                         ContextEngine.ContextType context,
                         GoalEngine.GoalType goal) {

        double score = 0;

        String text = safeText(el);
        String tag = el.getTagName();

        // 🎯 GOAL
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

        // 🧠 CONTEXT
        if (context == ContextEngine.ContextType.PRODUCT && text.contains("add"))
            score += 5;

        // 🔥 TAG WEIGHT
        if ("button".equals(tag)) score += 3;
        if ("a".equals(tag)) score += 2;

        // ❌ BAD ACTIONS
        if (text.contains("cancel") || text.contains("close")) score -= 5;

        return score + Math.random();
    }

    // ===================================================
    private boolean isValid(WebElement el) {
        try {
            return el.isDisplayed()
                    && el.isEnabled()
                    && el.getSize().height > 5
                    && el.getSize().width > 5;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSafe(WebElement el) {
        String t = safeText(el);
        return !(t.contains("logout") ||
                t.contains("delete") ||
                t.contains("remove") ||
                t.contains("sign out"));
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
            driver.switchTo().alert().accept();
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
    private String safeText(WebElement el) {
        try { return el.getText().toLowerCase(); }
        catch (Exception e) { return ""; }
    }

    private String buildKey(WebElement el) {
        try {
            return el.getTagName() + "|" +
                    el.getText() + "|" +
                    el.getAttribute("href");
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private String getDomain(String url) {
        try { return new URI(url).getHost(); }
        catch (Exception e) { return ""; }
    }
}