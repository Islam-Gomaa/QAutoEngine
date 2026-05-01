package engine.context;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ContextEngine {

    public enum ContextType {
        PRODUCT,
        CART,
        CHECKOUT,
        PAYMENT,
        AUTH,
        LISTING,
        DASHBOARD,
        SEARCH,
        GENERIC
    }

    // 🔥 smart cache (per URL)
    private static final Map<String, ContextType> cache =
            new ConcurrentHashMap<>();

    private static final int MAX_CACHE_SIZE = 100;

    // ===================================================
    // 🧠 MAIN DETECTION (AI SCORING ENGINE)
    // ===================================================
    public static ContextType detect(WebDriver driver) {

        if (driver == null) return ContextType.GENERIC;

        String url = safeUrl(driver);

        // 🔥 cache hit
        if (cache.containsKey(url)) {
            return cache.get(url);
        }

        Map<ContextType, Double> scores = new HashMap<>();

        // ===================================================
        // 1️⃣ URL SCORING
        // ===================================================
        merge(scores, scoreByUrl(url));

        // ===================================================
        // 2️⃣ DOM SCORING
        // ===================================================
        merge(scores, scoreByDom(driver));

        // ===================================================
        // 3️⃣ HEURISTICS BOOST (🔥 NEW)
        // ===================================================
        boostHeuristics(scores, driver);

        // ===================================================
        // 🔥 SELECT BEST
        // ===================================================
        ContextType best = scores.entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(ContextType.GENERIC);

        // ===================================================
        // 🔥 cache (bounded)
        // ===================================================
        if (cache.size() > MAX_CACHE_SIZE) {
            cache.clear(); // simple eviction (later ممكن LRU)
        }
        cache.put(url, best);

        System.out.println("🧠 Context → " + best + " | scores: " + scores);

        return best;
    }

    // ===================================================
    // ⚡ URL SCORING
    // ===================================================
    private static Map<ContextType, Double> scoreByUrl(String url) {

        Map<ContextType, Double> scores = new HashMap<>();

        if (url == null) return scores;

        url = url.toLowerCase();

        if (url.contains("product") || url.contains("details"))
            scores.put(ContextType.PRODUCT, 8.0);

        if (url.contains("cart") || url.contains("basket"))
            scores.put(ContextType.CART, 8.0);

        if (url.contains("checkout"))
            scores.put(ContextType.CHECKOUT, 10.0);

        if (url.contains("payment") || url.contains("pay"))
            scores.put(ContextType.PAYMENT, 10.0);

        if (url.contains("login") || url.contains("register"))
            scores.put(ContextType.AUTH, 7.0);

        if (url.contains("dashboard") || url.contains("admin"))
            scores.put(ContextType.DASHBOARD, 8.0);

        if (url.contains("search"))
            scores.put(ContextType.SEARCH, 6.0);

        if (url.contains("category") || url.contains("listing"))
            scores.put(ContextType.LISTING, 6.0);

        return scores;
    }

    // ===================================================
    // 🔍 DOM SCORING (SMART)
    // ===================================================
    private static Map<ContextType, Double> scoreByDom(WebDriver driver) {

        Map<ContextType, Double> scores = new HashMap<>();

        try {

            if (!driver.findElements(By.cssSelector("button.add-to-cart, .add-to-cart")).isEmpty()) {
                scores.merge(ContextType.PRODUCT, 15.0, Double::sum);
            }

            if (!driver.findElements(By.cssSelector(".cart-item, .basket-item")).isEmpty()) {
                scores.merge(ContextType.CART, 15.0, Double::sum);
            }

            if (!driver.findElements(By.cssSelector("form[action*='checkout'], .checkout")).isEmpty()) {
                scores.merge(ContextType.CHECKOUT, 18.0, Double::sum);
            }

            if (!driver.findElements(By.cssSelector("input[name='cardNumber'], input[name='cvv']")).isEmpty()) {
                scores.merge(ContextType.PAYMENT, 20.0, Double::sum);
            }

            if (!driver.findElements(By.cssSelector("input[type='password']")).isEmpty()) {
                scores.merge(ContextType.AUTH, 12.0, Double::sum);
            }

            if (!driver.findElements(By.cssSelector(".product-list, .items, .listing")).isEmpty()) {
                scores.merge(ContextType.LISTING, 10.0, Double::sum);
            }

        } catch (Exception ignored) {}

        return scores;
    }

    // ===================================================
    // 🔥 HEURISTICS BOOST (NEW)
    // ===================================================
    private static void boostHeuristics(Map<ContextType, Double> scores,
                                        WebDriver driver) {

        try {

            // 🔥 عدد الأزرار
            int buttons = driver.findElements(By.tagName("button")).size();

            if (buttons > 10) {
                scores.merge(ContextType.LISTING, 5.0, Double::sum);
            }

            // 🔥 وجود forms كتير
            int forms = driver.findElements(By.tagName("form")).size();

            if (forms > 2) {
                scores.merge(ContextType.CHECKOUT, 4.0, Double::sum);
            }

        } catch (Exception ignored) {}
    }

    // ===================================================
    // 🔧 MERGE SCORES
    // ===================================================
    private static void merge(Map<ContextType, Double> base,
                              Map<ContextType, Double> extra) {

        for (Map.Entry<ContextType, Double> e : extra.entrySet()) {
            base.merge(e.getKey(), e.getValue(), Double::sum);
        }
    }

    // ===================================================
    private static String safeUrl(WebDriver driver) {
        try {
            return driver.getCurrentUrl().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}