package engine.core;

import engine.decision.DecisionEngine;
import engine.learning.LearningEngine;
import engine.learning.LearningEngine.State;
import engine.context.ContextEngine;
import engine.goal.GoalEngine;
import engine.distribution.DistributionManager;
import org.openqa.selenium.*;
import utilities.Waits;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ScannerEngine {

    private final Set<String> seen =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final int maxPerPage = 200;

    // ===================================================
    // 🚀 MAIN ENTRY
    // ===================================================
    public Set<String> runAndCollect(WebDriver driver) {

        Set<String> result = new LinkedHashSet<>();

        try {

            Waits.waitForPageLoad(driver);

            String currentUrl = driver.getCurrentUrl();
            String baseDomain = getDomain(currentUrl);

            // 🧠 BUILD STATE (🔥 مهم جدًا)
            ContextEngine.ContextType context =
                    ContextEngine.detect(driver);

            GoalEngine.GoalType goal =
                    GoalEngine.getGoal();

            int domSize = driver.findElements(By.xpath("//*")).size();

            State state = LearningEngine.buildState(
                    context,
                    currentUrl,
                    goal,
                    domSize
            );

            // ===================================================
            // 🔍 COLLECT
            // ===================================================
            List<String> rawLinks = collectFromDom(driver);

            for (String url : rawLinks) {

                String normalized = normalize(url);

                if (!isValid(normalized, currentUrl, baseDomain, state))
                    continue;

                if (!seen.add(normalized)) continue;

                result.add(normalized);

                if (result.size() >= maxPerPage) break;
            }

            // ===================================================
            // 🧠 AI SORTING (🔥 upgraded)
            // ===================================================
            List<String> sorted = new ArrayList<>(result);

            sorted.sort((a, b) ->
                    Double.compare(
                            DecisionEngine.scoreNavigation(b, state),
                            DecisionEngine.scoreNavigation(a, state)
                    )
            );

            // ===================================================
            // 🔥 DISTRIBUTION
            // ===================================================
            DistributionManager.addAll(sorted);

            System.out.println("🔗 Scanner collected: " + sorted.size());

            return new LinkedHashSet<>(sorted);

        } catch (Exception e) {

            System.out.println("❌ Scanner failed: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    // ===================================================
    private List<String> collectFromDom(WebDriver driver) {

        List<String> urls = new ArrayList<>();

        try {

            List<WebElement> links = driver.findElements(By.tagName("a"));

            for (WebElement el : links) {

                try {

                    String href = el.getAttribute("href");

                    if (href != null) {
                        urls.add(href);
                    }

                } catch (StaleElementReferenceException ignored) {}
            }

        } catch (Exception ignored) {}

        return urls;
    }

    // ===================================================
    // 🧠 AI VALIDATION
    // ===================================================
    private boolean isValid(String url,
                            String currentUrl,
                            String baseDomain,
                            State state) {

        if (url.isEmpty()) return false;

        if (url.equals(normalize(currentUrl))) return false;

        if (url.startsWith("javascript")) return false;
        if (url.startsWith("mailto")) return false;
        if (url.startsWith("tel")) return false;

        if (!isSameDomain(url, baseDomain)) return false;

        // 🧠 AI scoring
        double score = DecisionEngine.scoreNavigation(url, state);

        return score > 1;
    }

    // ===================================================
    private boolean isSameDomain(String url, String baseDomain) {

        String domain = getDomain(url);

        return domain != null && domain.contains(baseDomain);
    }

    private String getDomain(String url) {

        try {
            return new URI(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    // ===================================================
    private String normalize(String url) {

        if (url == null) return "";

        try {

            url = url.trim();

            int hashIndex = url.indexOf("#");
            if (hashIndex != -1) {
                url = url.substring(0, hashIndex);
            }

            int queryIndex = url.indexOf("?");
            if (queryIndex != -1) {
                url = url.substring(0, queryIndex);
            }

            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

        } catch (Exception ignored) {}

        return url;
    }
}