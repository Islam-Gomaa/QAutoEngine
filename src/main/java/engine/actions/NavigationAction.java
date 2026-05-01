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

public class NavigationAction implements Action {

    private static final int MAX_LINKS = 30;

    @Override
    public void execute(WebDriver driver) {

        System.out.println("🌍 [Navigation AI] Start");

        String baseDomain = getDomain(driver.getCurrentUrl());

        ContextEngine.ContextType context = ContextEngine.detect(driver);
        GoalEngine.GoalType goal = GoalEngine.getGoal();

        List<WebElement> links = driver.findElements(By.tagName("a"));

        List<WebElement> validLinks = new ArrayList<>();

        for (WebElement link : links) {

            try {
                String href = link.getAttribute("href");

                if (href == null || !href.startsWith("http")) continue;

                String normalized = normalize(href);

                if (!getDomain(normalized).contains(baseDomain)) continue;
                if (SessionState.shouldAvoidUrl(normalized)) continue;
                if (isBlocked(normalized)) continue;

                validLinks.add(link);

            } catch (StaleElementReferenceException ignored) {}
        }

        // 🔥 Dedup
        validLinks = validLinks.stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                el -> normalize(el.getAttribute("href")),
                                el -> el,
                                (a, b) -> a
                        ),
                        map -> new ArrayList<>(map.values())
                ));

        // 🔥 Sorting
        validLinks.sort((a, b) -> Double.compare(
                score(b.getAttribute("href"), context, goal),
                score(a.getAttribute("href"), context, goal)
        ));

        int count = 0;

        for (WebElement link : validLinks) {

            if (count++ >= MAX_LINKS) break;

            try {

                String url = normalize(link.getAttribute("href"));

                double linkScore = score(url, context, goal);

                System.out.println("➡️ Trying: " + url + " (score: " + linkScore + ")");

                DebugUtil.highlight(driver, link);
                DebugUtil.slow(400);

                String beforeUrl = driver.getCurrentUrl();
                int beforeDom = driver.findElements(By.xpath("//*")).size();
                String beforeTitle = driver.getTitle();

                safeClick(driver, link);

                DebugUtil.slow(900);

                String afterUrl = driver.getCurrentUrl();
                int afterDom = driver.findElements(By.xpath("//*")).size();
                String afterTitle = driver.getTitle();

                boolean changed =
                        !beforeUrl.equals(afterUrl)
                                || beforeDom != afterDom
                                || !beforeTitle.equals(afterTitle);

                if (changed) {

                    SessionState.markUrlVisited(url);

                    System.out.println("✅ Navigation SUCCESS");
                    return;
                }

            } catch (Exception e) {

                System.out.println("❌ Navigation failed → trying next");
            }
        }

        System.out.println("⚠️ No navigation executed");
    }

    // ===================================================
    private double score(String url,
                         ContextEngine.ContextType context,
                         GoalEngine.GoalType goal) {

        double score = 0;

        url = url.toLowerCase();

        if (goal != null) {

            switch (goal) {

                case ADD_TO_CART:
                    if (url.contains("product")) score += 10;
                    break;

                case REACH_CHECKOUT:
                    if (url.contains("cart")) score += 12;
                    if (url.contains("checkout")) score += 15;
                    break;

                case COMPLETE_PAYMENT:
                    if (url.contains("checkout")) score += 15;
                    if (url.contains("payment")) score += 18;
                    break;
            }
        }

        if (context == ContextEngine.ContextType.GENERIC &&
                url.contains("product")) score += 6;

        if (context == ContextEngine.ContextType.CART &&
                url.contains("checkout")) score += 8;

        if (url.contains("next")) score += 4;
        if (url.contains("continue")) score += 4;
        if (url.contains("details")) score += 3;

        return score + Math.random();
    }

    // ===================================================
    private void safeClick(WebDriver driver, WebElement el) {

        try {
            Waits.waitForClickable(driver, el).click();
        } catch (Exception e) {
            ((JavascriptExecutor) driver)
                    .executeScript("arguments[0].click();", el);
        }
    }

    // ===================================================
    private boolean isBlocked(String url) {

        url = url.toLowerCase();

        return url.contains("logout") ||
                url.contains("signout") ||
                url.contains("delete") ||
                url.contains("remove") ||
                url.contains("facebook") ||
                url.contains("twitter") ||
                url.contains("instagram");
    }

    private String normalize(String url) {

        if (url == null) return "";

        url = url.split("#")[0];
        url = url.split("\\?")[0];

        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        return url;
    }

    private String getDomain(String url) {
        try {
            return new URI(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }
}