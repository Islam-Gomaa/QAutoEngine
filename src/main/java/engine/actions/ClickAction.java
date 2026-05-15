package engine.actions;

import engine.context.ContextEngine;
import ai.intelligence.ProgressEngine;
import ai.intelligence.ScenarioTracker;
import ai.learning.State;
import engine.state.SessionState;

import org.openqa.selenium.*;
import utilities.Waits;
import utilities.DebugUtil;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public class ClickAction implements Action {

    private static final int MAX_ATTEMPTS = 15;

    @Override
    public void execute(WebDriver driver) {

        System.out.println("🧠 [ClickAction AI V6]");

        String baseDomain = getDomain(driver.getCurrentUrl());
        ContextEngine.ContextType context = ContextEngine.detect(driver);

        List<WebElement> elements = findClickableElements(driver);

        if (elements.isEmpty()) return;

        State prevState = buildState(driver);

        // 🔥 Smart filtering + sorting
        elements = elements.stream()
                .filter(this::isValid)
                .filter(this::isSafe)
                .distinct()
                .sorted((a, b) -> Double.compare(score(b, context, prevState), score(a, context, prevState)))
                .limit(40)
                .collect(Collectors.toList());

        int attempts = 0;

        for (WebElement el : elements) {

            if (attempts++ >= MAX_ATTEMPTS) break;

            try {

                if (isExternal(el, baseDomain)) continue;

                String key = buildKey(el);

                // 💣 MEMORY AVOID
                if (SessionState.shouldAvoidElement(key)) continue;

                scrollIntoView(driver, el);
                DebugUtil.highlight(driver, el);

                State before = prevState;

                safeClick(driver, el);
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

                if (progress > 1.5) {

                    SessionState.markElementVisited(key);
                    SessionState.markStateVisited(after);

                    ScenarioTracker.record("ClickAction", after.url, after, progress);

                    System.out.println("✅ Click SUCCESS → " + progress);
                    return;
                }

            } catch (Exception ignored) {}
        }

        System.out.println("⚠️ ClickAction no useful interaction");
    }

    // ===================================================
    // 💣 INTENT + CONTEXT SCORING
    // ===================================================
    private double score(WebElement el,
                         ContextEngine.ContextType context,
                         State state) {

        String text = safe(el.getText()).toLowerCase();
        String cls = safe(el.getAttribute("class")).toLowerCase();
        String id = safe(el.getAttribute("id")).toLowerCase();
        String aria = safe(el.getAttribute("aria-label")).toLowerCase();

        String key = text + " " + cls + " " + id + " " + aria;

        double score = 0;

        // 🔥 PRIMARY CTA
        if (key.matches(".*(next|continue|submit|login|sign in).*")) score += 15;
        if (key.matches(".*(checkout|pay|confirm|place order).*")) score += 18;

        // 🔥 SECONDARY
        if (key.matches(".*(view|details|open|start).*")) score += 8;

        // 🔥 BUTTON SIGNAL
        if (cls.contains("btn") || el.getTagName().equals("button")) score += 5;

        // 🔥 CONTEXT AWARENESS
        if (state != null) {

            if (state.hasForm && key.contains("submit")) score += 10;
            if (state.hasLinks && el.getTagName().equals("a")) score += 5;
        }

        // 🔥 POSITION (UX)
        try {
            Point p = el.getLocation();
            if (p.getY() < 800) score += 2;
        } catch (Exception ignored) {}

        // ❌ NEGATIVE SIGNALS
        if (key.matches(".*(cancel|close|back|delete|logout).*")) score -= 20;

        // 🔥 EXPLORATION
        score += Math.random();

        return score;
    }

    // ===================================================
    // 🔥 SAFE CLICK
    // ===================================================
    private void safeClick(WebDriver driver, WebElement el) {

        try {
            Waits.waitForClickable(driver, el).click();
        } catch (Exception e) {

            try {
                ((JavascriptExecutor) driver)
                        .executeScript("arguments[0].click();", el);
            } catch (Exception ignored) {}
        }
    }

    // ===================================================
    private boolean isValid(WebElement el) {
        try { return el.isDisplayed(); } catch (Exception e) { return false; }
    }

    private boolean isSafe(WebElement el) {
        String t = safe(el.getText()).toLowerCase();
        return !(t.contains("delete") || t.contains("logout"));
    }

    private boolean isExternal(WebElement el, String baseDomain) {

        try {
            String href = el.getAttribute("href");
            if (href == null) return false;
            return !getDomain(href).contains(baseDomain);
        } catch (Exception e) { return false; }
    }

    private void scrollIntoView(WebDriver driver, WebElement el) {
        try {
            ((JavascriptExecutor) driver)
                    .executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        } catch (Exception ignored) {}
    }

    private String buildKey(WebElement el) {
        return el.getTagName() + "|" + safe(el.getText()) + "|" + safe(el.getAttribute("id"));
    }

    private List<WebElement> findClickableElements(WebDriver driver) {

        return driver.findElements(By.xpath(
                "//button | //a | //input[@type='submit'] | //*[@role='button']"
        ));
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
                "CLICK",
                hasForm,
                hasLinks
        );
    }

    private String getDomain(String url) {
        try { return new URI(url).getHost(); } catch (Exception e) { return ""; }
    }

    private String safe(String s) { return s == null ? "" : s; }
}