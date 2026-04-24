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

public class NavigationAction implements Action {

    private static final int MAX_LINKS = 30;

    @Override
    public void execute(WebDriver driver) {

        System.out.println("🌍 [Navigation AI] Start");

        String baseDomain = getDomain(driver.getCurrentUrl());

        ContextEngine.ContextType context = ContextEngine.detect(driver);
        GoalEngine.GoalType goal = GoalEngine.getGoal();

        List<WebElement> links = driver.findElements(By.tagName("a"));

        List<String> urls = new ArrayList<>();

        for (WebElement link : links) {

            try {
                String href = link.getAttribute("href");

                if (href == null || !href.startsWith("http")) continue;

                // 🔒 domain filter
                if (!getDomain(href).contains(baseDomain)) continue;

                // 🔁 skip visited
                if (SessionState.isUrlVisited(href)) continue;

                // 🚫 block unwanted
                if (isBlocked(href)) continue;

                urls.add(normalize(href));

            } catch (StaleElementReferenceException ignored) {}
        }

        // إزالة التكرار
        urls = urls.stream().distinct().collect(Collectors.toList());

        // 🔥 ترتيب بالـ AI
        urls.sort(Comparator.comparingDouble(url -> -score(url, context, goal)));

        int count = 0;

        for (String url : urls) {

            if (count++ >= MAX_LINKS) break;

            try {

                System.out.println("➡️ Navigating to: " + url);

                String before = driver.getCurrentUrl();

                driver.navigate().to(url);
                Waits.waitForPageLoad(driver);

                String after = driver.getCurrentUrl();

                // 🧠 Learning
                BehaviorGraph.record(before, "NavigationAction", after);
                BehaviorStore.recordSuccess("NavigationAction");

                return; // 🔥 خطوة واحدة ذكية

            } catch (Exception e) {

                BehaviorStore.recordFailure("NavigationAction");
                System.out.println("❌ Navigation failed");
            }
        }

        System.out.println("⚠️ No navigation executed");
    }

    // ===================================================
    // 🧠 SCORE
    // ===================================================
    private double score(String url,
                         ContextEngine.ContextType context,
                         GoalEngine.GoalType goal) {

        double score = 0;

        url = url.toLowerCase();

        // 🎯 goal-driven
        if (goal != null) {

            switch (goal) {
                case ADD_TO_CART:
                    if (url.contains("product")) score += 5;
                    break;

                case REACH_CHECKOUT:
                    if (url.contains("cart") || url.contains("checkout")) score += 10;
                    break;

                case COMPLETE_PAYMENT:
                    if (url.contains("checkout") || url.contains("payment")) score += 10;
                    break;
            }
        }

        // 🧠 context
        if (context == ContextEngine.ContextType.GENERIC && url.contains("product")) score += 5;
        if (context == ContextEngine.ContextType.CART && url.contains("checkout")) score += 5;

        // 📊 learning
        score += BehaviorStore.getScore("NavigationAction") * 5;

        // 🔑 keywords
        if (url.contains("next")) score += 2;
        if (url.contains("continue")) score += 2;

        return score;
    }

    // ===================================================
    // 🚫 BLOCK
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

    // ===================================================
    // 🧹 NORMALIZE
    // ===================================================
    private String normalize(String url) {

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