package engine.actions;

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

public class NavigationAction implements Action {

    private static final int MAX_LINKS = 40;

    @Override
    public void execute(WebDriver driver) {

        System.out.println("🧠 [NavigationAction AI V6]");

        String baseDomain = getDomain(driver.getCurrentUrl());

        List<WebElement> links = findLinks(driver);

        if (links.isEmpty()) {
            System.out.println("⚠️ No links found");
            return;
        }

        State prevState = buildState(driver);

        // 🔥 Clean + Rank
        links = links.stream()
                .filter(this::isValid)
                .filter(link -> !isExternal(link, baseDomain))
                .distinct()
                .sorted((a, b) -> Double.compare(score(b, prevState), score(a, prevState)))
                .limit(MAX_LINKS)
                .collect(Collectors.toList());

        int attempts = 0;

        for (WebElement link : links) {

            try {

                String href = normalize(link.getAttribute("href"));
                if (href.isEmpty()) continue;

                if (SessionState.shouldAvoidUrl(href)) continue;

                DebugUtil.highlight(driver, link);
                scrollIntoView(driver, link);

                State before = prevState;

                safeClick(driver, link);

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

                    SessionState.markUrlVisited(after.url);
                    SessionState.markStateVisited(after);

                    ScenarioTracker.record(
                            "NavigationAction",
                            after.url,
                            after,
                            progress
                    );

                    System.out.println("✅ Navigation SUCCESS → " + progress);
                    return;
                }

                attempts++;

            } catch (Exception ignored) {}
        }

        System.out.println("⚠️ NavigationAction no useful path");
    }

    // ===================================================
    // 💣 SCORING (STATE-AWARE)
    // ===================================================
    private double score(WebElement link, State state) {

        String href = safe(link.getAttribute("href")).toLowerCase();
        String text = safe(link.getText()).toLowerCase();
        String cls = safe(link.getAttribute("class")).toLowerCase();

        String key = href + " " + text + " " + cls;

        double score = 0;

        // 🔥 HIGH VALUE PAGES
        if (key.matches(".*(product|details|view).*")) score += 12;
        if (key.matches(".*(cart|basket).*")) score += 15;
        if (key.matches(".*(checkout|payment).*")) score += 18;

        // 🔥 FLOW
        if (key.matches(".*(next|continue|step).*")) score += 10;

        // 🔥 DEPTH
        if (href.split("/").length > 4) score += 3;

        // 🔥 UI SIGNAL
        if (cls.contains("nav") || cls.contains("menu")) score += 2;

        // 🔥 CONTEXT
        if (state != null && state.hasLinks) score += 2;

        // ❌ NEGATIVE
        if (key.matches(".*(logout|delete|remove|cancel).*")) score -= 25;

        // 🔥 EXPLORATION
        score += Math.random();

        return score;
    }

    // ===================================================
    private void safeClick(WebDriver driver, WebElement el) {

        try {
            el.click();
        } catch (Exception e) {

            try {
                ((JavascriptExecutor) driver)
                        .executeScript("arguments[0].click();", el);
            } catch (Exception ignored) {}
        }
    }

    private List<WebElement> findLinks(WebDriver driver) {

        return driver.findElements(By.xpath(
                "//a[@href] | //*[@role='link']"
        ));
    }

    private boolean isValid(WebElement el) {
        try { return el.isDisplayed(); } catch (Exception e) { return false; }
    }

    private boolean isExternal(WebElement el, String base) {
        try {
            String href = el.getAttribute("href");
            if (href == null) return true;
            return !getDomain(href).contains(base);
        } catch (Exception e) { return true; }
    }

    private String normalize(String url) {
        if (url == null) return "";
        return url.split("\\?")[0];
    }

    private void scrollIntoView(WebDriver driver, WebElement el) {
        try {
            ((JavascriptExecutor) driver)
                    .executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        } catch (Exception ignored) {}
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
                "NAV",
                hasForm,
                hasLinks
        );
    }

    private String getDomain(String url) {
        try { return new URI(url).getHost(); } catch (Exception e) { return ""; }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}