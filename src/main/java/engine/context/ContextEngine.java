package engine.context;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

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

    // ===================================================
    // 🧠 FULL DETECTION (WebDriver + DOM)
    // ===================================================
    public static ContextType detect(WebDriver driver) {

        if (driver == null) return ContextType.GENERIC;

        String url = safeUrl(driver);

        // 🔥 1. URL-based detection (fast)
        ContextType byUrl = detect(url);

        // 🔥 2. DOM-based detection (strong)
        ContextType byDom = detectFromDom(driver);

        // 🔥 3. Merge logic (priority)
        return merge(byUrl, byDom);
    }

    // ===================================================
    // ⚡ LIGHT DETECTION (URL only) → used by ActionDecider
    // ===================================================
    public static ContextType detect(String url) {

        if (url == null) return ContextType.GENERIC;

        url = url.toLowerCase();

        if (url.contains("product") || url.contains("details")) return ContextType.PRODUCT;
        if (url.contains("cart") || url.contains("basket")) return ContextType.CART;
        if (url.contains("checkout")) return ContextType.CHECKOUT;
        if (url.contains("payment") || url.contains("pay")) return ContextType.PAYMENT;
        if (url.contains("login") || url.contains("register") || url.contains("auth")) return ContextType.AUTH;
        if (url.contains("dashboard") || url.contains("admin")) return ContextType.DASHBOARD;
        if (url.contains("search")) return ContextType.SEARCH;
        if (url.contains("category") || url.contains("listing")) return ContextType.LISTING;

        return ContextType.GENERIC;
    }

    // ===================================================
    // 🔍 DOM DETECTION (SMART)
    // ===================================================
    private static ContextType detectFromDom(WebDriver driver) {

        try {

            if (!driver.findElements(By.cssSelector("button.add-to-cart, .add-to-cart")).isEmpty()) {
                return ContextType.PRODUCT;
            }

            if (!driver.findElements(By.cssSelector(".cart-item, .basket-item")).isEmpty()) {
                return ContextType.CART;
            }

            if (!driver.findElements(By.cssSelector("form[action*='checkout'], .checkout")).isEmpty()) {
                return ContextType.CHECKOUT;
            }

            if (!driver.findElements(By.cssSelector("input[name='cardNumber'], input[name='cvv']")).isEmpty()) {
                return ContextType.PAYMENT;
            }

            if (!driver.findElements(By.cssSelector("input[type='password']")).isEmpty()) {
                return ContextType.AUTH;
            }

            if (!driver.findElements(By.cssSelector(".product-list, .items, .listing")).isEmpty()) {
                return ContextType.LISTING;
            }

        } catch (Exception ignored) {}

        return ContextType.GENERIC;
    }

    // ===================================================
    // 🧠 MERGE LOGIC (🔥 مهم)
    // ===================================================
    private static ContextType merge(ContextType byUrl, ContextType byDom) {

        // لو DOM اكتشف حاجة واضحة → نثق فيه أكتر
        if (byDom != ContextType.GENERIC) {
            return byDom;
        }

        return byUrl;
    }

    // ===================================================
    // 🛡️ SAFE URL
    // ===================================================
    private static String safeUrl(WebDriver driver) {

        try {
            return driver.getCurrentUrl().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}