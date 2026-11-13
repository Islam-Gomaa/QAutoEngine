package qaengine.web.bakeoff;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.bidi.module.LogInspector;
import org.openqa.selenium.bidi.module.Network;
import org.openqa.selenium.bidi.network.AddInterceptParameters;
import org.openqa.selenium.bidi.network.ContinueRequestParameters;
import org.openqa.selenium.bidi.network.InterceptPhase;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

final class SeleniumBidiProbe implements Probe {
    private final WebDriver driver;
    private final Network network;
    private final LogInspector logs;
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<String> signals = new CopyOnWriteArrayList<>();

    SeleniumBidiProbe() {
        ChromeOptions o = new ChromeOptions();
        o.setBinary(System.getProperty("chromebin"));
        o.addArguments("--headless=new");
        o.setCapability("webSocketUrl", true);
        driver = new ChromeDriver(o);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(10));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(8));
        network = new Network(driver);
        logs = new LogInspector(driver);
        logs.onConsoleEntry(e -> signals.add("console:" + e.getText()));
        logs.onJavaScriptException(e -> signals.add("exception:" + e.getText()));
        network.onResponseCompleted(r -> signals.add("status:" + r.getResponseData().getStatus() + " " + r.getRequest().getUrl()));
    }

    @Override public String name() { return "selenium-bidi"; }

    @Override public void guard(BiPredicate<String, String> block) {
        network.addIntercept(new AddInterceptParameters(InterceptPhase.BEFORE_REQUEST_SENT));
        network.onBeforeRequestSent(e -> handlers.submit(() -> {
            if (!e.isBlocked()) return;
            var r = e.getRequest();
            if (block.test(r.getMethod(), r.getUrl())) network.failRequest(r.getRequestId());
            else network.continueRequest(new ContinueRequestParameters(r.getRequestId()));
        }));
    }

    @Override public void open(String url) { driver.get(url); }
    @Override public Object js(String s) { return ((JavascriptExecutor) driver).executeScript(s); }
    @Override public void click(String css) { driver.findElement(By.cssSelector(css)).click(); }

    @Override public void inNewTab(String url, String script) {
        String first = driver.getWindowHandle();
        driver.switchTo().newWindow(org.openqa.selenium.WindowType.TAB);
        driver.get(url);
        js(script);
        try { Thread.sleep(800); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        driver.close();
        driver.switchTo().window(first);
    }

    @Override public List<String> drainSignals() { var c = new ArrayList<>(signals); signals.clear(); return c; }

    @Override public byte[] maskedScreenshot(String css) {
        js("document.querySelectorAll('" + css + "').forEach(e=>{e.style.color='black';e.style.background='black'})");
        return ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
    }

    @Override public void close() {
        try { logs.close(); } catch (Exception ignored) { }
        try { network.close(); } catch (Exception ignored) { }
        driver.quit();
        handlers.shutdownNow();
    }
}
