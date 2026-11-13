package utilities;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import io.qameta.allure.Allure;
import java.io.FileInputStream;

public class ScreenshotUtil {

    public static void capture(WebDriver driver, String fileName) {
        try {
            File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);

            File dest = new File("screenshots/" + fileName + ".png");

            dest.getParentFile().mkdirs();

            Files.copy(src.toPath(), dest.toPath());

            System.out.println("📸 Screenshot saved: " + dest.getAbsolutePath());

        } catch (Exception e) {
            System.out.println("❌ Failed to capture screenshot");
        }
    }

    public static void captureAndAttach(WebDriver driver, String name) {
        try {
            File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);

            File dest = new File("screenshots/" + name + ".png");
            dest.getParentFile().mkdirs();

            Files.copy(src.toPath(), dest.toPath());

            // attach to Allure from files
            Allure.addAttachment(
                    name,
                    new FileInputStream(dest)
            );

            System.out.println("📸 Screenshot saved + attached: " + dest.getAbsolutePath());

        } catch (Exception e) {
            System.out.println("❌ Failed to capture screenshot");
        }
    }
}