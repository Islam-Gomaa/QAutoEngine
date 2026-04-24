package engine.core;

import engine.decision.DecisionEngine;
import engine.optimization.PathOptimizer;
import engine.distribution.DistributionManager;
import org.openqa.selenium.*;
import utilities.Waits;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ScannerEngine {

    // 🔥 session-based seen (مش static)
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

            List<String> rawLinks = collectFromDom(driver);

            for (String url : rawLinks) {

                String normalized = normalize(url);

                if (!isValid(normalized, currentUrl, baseDomain)) continue;

                // 🔁 dedupe
                if (!seen.add(normalized)) continue;

                result.add(normalized);

                if (result.size() >= maxPerPage) break;
            }

            // 🔥 AI Prioritization
            List<String> prioritized = PathOptimizer.prioritize(new ArrayList<>(result));

            // 🔥 Distribution
            DistributionManager.addAll(prioritized);

            System.out.println("🔗 Scanner collected: " + prioritized.size());

            return new LinkedHashSet<>(prioritized);

        } catch (Exception e) {
            System.out.println("❌ Scanner failed: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    // ===================================================
    // 🔍 COLLECT
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
    // 🔥 VALIDATION (CORE FILTER)
    // ===================================================
    private boolean isValid(String url, String currentUrl, String baseDomain) {

        if (url.isEmpty()) return false;

        // 🚫 نفس الصفحة
        if (url.equals(normalize(currentUrl))) return false;

        // 🚫 invalid protocols
        if (url.startsWith("javascript")) return false;
        if (url.startsWith("mailto")) return false;
        if (url.startsWith("tel")) return false;

        // 🔐 domain guard
        if (!isSameDomain(url, baseDomain)) return false;

        // 🧠 AI decision
        return DecisionEngine.shouldNavigate(url);
    }

    // ===================================================
    // 🌐 DOMAIN
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
    // 🧹 NORMALIZATION
    // ===================================================
    private String normalize(String url) {

        if (url == null) return "";

        try {
            url = url.trim();

            // remove fragments
            int hashIndex = url.indexOf("#");
            if (hashIndex != -1) {
                url = url.substring(0, hashIndex);
            }

            // remove query
            int queryIndex = url.indexOf("?");
            if (queryIndex != -1) {
                url = url.substring(0, queryIndex);
            }

            // remove trailing slash
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

        } catch (Exception ignored) {}

        return url;
    }
}