package utilities;

import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

import java.time.Duration;

public class DriverFactory {

    // 🔥 خليها true علشان نشوف الـ UI
    private static final boolean DEBUG = true;

    public static WebDriver createDriver(String browser) {

        if (browser == null) {
            throw new RuntimeException("Browser name is null");
        }

        browser = browser.trim().toLowerCase();

        WebDriver driver;

        switch (browser) {

            // =========================
            // 🌐 CHROME
            // =========================
            case "chrome":
            case "headlesschrome": {

                ChromeOptions options = new ChromeOptions();

                // 🔥 Performance Boost
                options.addArguments("--disable-extensions");
                options.addArguments("--disable-notifications");
                options.addArguments("--disable-infobars");
                options.addArguments("--disable-popup-blocking");
                options.addArguments("--disable-background-networking");
                options.addArguments("--disable-renderer-backgrounding");
                options.addArguments("--no-first-run");
                options.addArguments("--no-default-browser-check");

                // 🔥 Optional (اقفل الصور لو مش محتاجها)
                options.addArguments("--blink-settings=imagesEnabled=false");

                // SSL + Language
                options.setAcceptInsecureCerts(true);
                options.addArguments("--ignore-certificate-errors");
                options.addArguments("--lang=en");
                options.addArguments("--accept-lang=en-US");

                // 🔥 Window أسرع من maximize
                options.addArguments("--window-size=1400,900");

                // 🔥 أهم تحسين
                options.setPageLoadStrategy(PageLoadStrategy.EAGER);

                // 🔥 Headless فقط لو DEBUG = false
                if (browser.contains("headless") && !DEBUG) {
                    options.addArguments("--headless=new");
                    options.addArguments("--window-size=1920,1080");
                }

                // stability
                options.addArguments("--disable-dev-shm-usage");
                options.addArguments("--no-sandbox");

                driver = new ChromeDriver(options);
                break;
            }

            // =========================
            // 🦊 FIREFOX
            // =========================
            case "firefox":
            case "headlessfirefox": {

                FirefoxOptions options = new FirefoxOptions();

                options.setAcceptInsecureCerts(true);

                // 🔥 Performance (basic)
                options.addArguments("--width=1400");
                options.addArguments("--height=900");

                // 🔥 Headless condition
                if (browser.contains("headless") && !DEBUG) {
                    options.addArguments("--headless");
                }

                // 🔥 مشابه للـ eager (تقريبًا)
                options.setPageLoadStrategy(PageLoadStrategy.EAGER);

                driver = new FirefoxDriver(options);
                break;
            }

            default:
                throw new RuntimeException("Browser not supported: " + browser);
        }

        // =========================
        // ⏱️ Common Setup
        // =========================

        // قللناها علشان السرعة
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(3));

        return driver;
    }
}