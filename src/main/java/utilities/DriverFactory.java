package utilities;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

public class DriverFactory {

    public static WebDriver createDriver(String browser) {

        if (browser == null) {
            throw new RuntimeException("Browser name is null");
        }
        browser = browser.trim().toLowerCase();

        switch (browser) {

            case "chrome":
            case "headlesschrome":

                ChromeOptions chromeOptions = new ChromeOptions();

                // ignore SSL certificate errors
                chromeOptions.setAcceptInsecureCerts(true);
                chromeOptions.addArguments("--ignore-certificate-errors");
                chromeOptions.addArguments("--lang=en");
                chromeOptions.addArguments("--accept-lang=en-US");

                if (browser.contains("headless")) {
                    chromeOptions.addArguments("--headless");
                    chromeOptions.addArguments("--window-size=1920,1080");
                }
                return new ChromeDriver(chromeOptions);


            case "firefox":
            case "headlessfirefox":

                FirefoxOptions firefoxOptions = new FirefoxOptions();

                // ignore SSL certificate errors
                firefoxOptions.setAcceptInsecureCerts(true);

                if (browser.contains("headless")) {
                    firefoxOptions.addArguments("--headless");
                }
                return new FirefoxDriver(firefoxOptions);

            default:
                throw new RuntimeException("Browser not supported: " + browser);
        }
    }
}