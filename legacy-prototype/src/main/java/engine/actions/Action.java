package engine.actions;

import org.openqa.selenium.WebDriver;

public interface Action {
    void execute(WebDriver driver);
}