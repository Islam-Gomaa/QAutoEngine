package engine.actions;

import engine.context.ContextEngine;
import engine.intelligence.ProgressEngine;
import engine.intelligence.ScenarioTracker;
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

        System.out.println("🧠 [ClickAction AI ULTRA]");

        String baseDomain = getDomain(driver.getCurrentUrl());
        ContextEngine.ContextType context = ContextEngine.detect(driver);

        List<WebElement> elements = findClickableElements(driver);

        if (elements.isEmpty()) return;

        // 🔥 Deduplicate + Smart Sort
        elements = elements.stream()
                .filter(this::isValid)
                .filter(this::isSafe)
                .distinct()
                .sorted((a, b) -> Double.compare(score(b, context), score(a, context)))
                .limit(50)
                .collect(Collectors.toList());

        int attempts = 0;

        for (WebElement el : elements) {

            if (attempts++ >= MAX_ATTEMPTS) break;

            try {

                if (isExternal(el, baseDomain)) continue;

                String key = buildKey(el);
                if (SessionState.shouldAvoidElement(key)) continue;

                scrollIntoView(driver, el);
                DebugUtil.highlight(driver, el);

                String beforeUrl = driver.getCurrentUrl();
                int beforeDom = driver.findElements(By.xpath("//*")).size();

                safeClick(driver, el);

                Waits.waitForPageLoad(driver);

                String afterUrl = driver.getCurrentUrl();
                int afterDom = driver.findElements(By.xpath("//*")).size();

                double progress = ProgressEngine.evaluateProgress(
                        driver, beforeUrl, afterUrl, beforeDom, afterDom
                );

                if (progress > 1.5) {

                    SessionState.markElementVisited(key);
                    ScenarioTracker.record("ClickAction", afterUrl, progress);

                    System.out.println("✅ Click SUCCESS → " + progress);
                    return;
                }

            } catch (Exception ignored) {}
        }

        System.out.println("⚠️ ClickAction no useful interaction");
    }

    // ===================================================
    // 💣 INTENT-BASED SCORING (REAL AI LOGIC)
    // ===================================================
    private double score(WebElement el, ContextEngine.ContextType context) {

        String text = safe(el.getText()).toLowerCase();
        String cls = safe(el.getAttribute("class")).toLowerCase();
        String id = safe(el.getAttribute("id")).toLowerCase();
        String aria = safe(el.getAttribute("aria-label")).toLowerCase();

        String key = text + " " + cls + " " + id + " " + aria;

        double score = 0;

        // 🔥 PRIMARY ACTIONS (CTA)
        if (key.matches(".*(next|continue|submit|login|sign in).*")) score += 15;
        if (key.matches(".*(checkout|pay|confirm|place order).*")) score += 18;

        // 🔥 SECONDARY
        if (key.matches(".*(view|details|open|start).*")) score += 8;

        // 🔥 BUTTON SIGNAL
        if (cls.contains("btn") || el.getTagName().equals("button")) score += 5;

        // 🔥 POSITION (important UX insight)
        try {
            Point p = el.getLocation();
            if (p.getY() < 800) score += 2; // above fold
        } catch (Exception ignored) {}

        // ❌ NEGATIVE SIGNALS
        if (key.matches(".*(cancel|close|back|delete|logout).*")) score -= 15;

        // 🔥 RANDOM EXPLORATION
        score += Math.random();

        return score;
    }

    // ===================================================
    // 🔥 SMART CLICK (fallback)
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

    private String getDomain(String url) {
        try { return new URI(url).getHost(); } catch (Exception e) { return ""; }
    }

    private String safe(String s) { return s == null ? "" : s; }
}