package engine.core;

import org.openqa.selenium.*;

public class StateChangeDetector {

    public static class StateSnapshot {
        public String url;
        public int domSize;
        public String title;

        public StateSnapshot(WebDriver driver) {
            this.url = driver.getCurrentUrl();
            this.domSize = driver.findElements(By.xpath("//*")).size();
            this.title = driver.getTitle();
        }
    }

    public static boolean hasChanged(WebDriver driver, StateSnapshot before) {

        try {
            String afterUrl = driver.getCurrentUrl();
            int afterDom = driver.findElements(By.xpath("//*")).size();
            String afterTitle = driver.getTitle();

            boolean urlChanged = !before.url.equals(afterUrl);
            boolean domChanged = before.domSize != afterDom;
            boolean titleChanged = !before.title.equals(afterTitle);

            return urlChanged || domChanged || titleChanged;

        } catch (Exception e) {
            return false;
        }
    }
}