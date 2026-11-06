package qaengine.web.bakeoff;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v155.fetch.Fetch;
import org.openqa.selenium.devtools.v155.log.Log;
import org.openqa.selenium.devtools.v155.network.Network;
import org.openqa.selenium.devtools.v155.network.model.ErrorReason;
import org.openqa.selenium.devtools.v155.runtime.Runtime;

final class SeleniumCdpProbe implements Probe {
    private final ChromeDriver driver;
    private final DevTools dev;
    private final List<String> signals = new CopyOnWriteArrayList<>();

    SeleniumCdpProbe() {
        ChromeOptions o = new ChromeOptions();
        o.setBinary(System.getProperty("chromebin"));
        o.addArguments("--headless=new");
        driver = new ChromeDriver(o);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(10));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(8));
        dev = driver.getDevTools();
        dev.createSession();
        dev.send(Runtime.enable());
        dev.send(Log.enable());
        dev.send(Network.enable(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        dev.addListener(Runtime.consoleAPICalled(), e -> signals.add("console:" + e.getType()));
        dev.addListener(Runtime.exceptionThrown(), e -> signals.add("exception:" + e.getExceptionDetails().getText()));
        dev.addListener(Network.responseReceived(), e -> signals.add("status:" + e.getResponse().getStatus() + " " + e.getResponse().getUrl()));
    }

    @Override public String name() { return "selenium-cdp"; }

    @Override public void guard(BiPredicate<String, String> block) {
        dev.send(Fetch.enable(Optional.empty(), Optional.empty()));
        dev.addListener(Fetch.requestPaused(), e -> {
            if (block.test(e.getRequest().getMethod(), e.getRequest().getUrl())) {
                dev.send(Fetch.failRequest(e.getRequestId(), ErrorReason.BLOCKEDBYCLIENT));
            } else {
                dev.send(Fetch.continueRequest(e.getRequestId(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
            }
        });
    }

    @Override public void open(String url) { driver.get(url); }
    @Override public Object js(String s) { return ((JavascriptExecutor) driver).executeScript(s); }
    @Override public void click(String css) { driver.findElement(By.cssSelector(css)).click(); }

    @Override public void inNewTab(String url, String script) {
        String first = driver.getWindowHandle();
        driver.switchTo().newWindow(WindowType.TAB);
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

    @Override public void close() { try { dev.close(); } catch (Exception ignored) { } driver.quit(); }
}
