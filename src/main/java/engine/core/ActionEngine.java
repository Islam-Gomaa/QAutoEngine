package engine.core;

import engine.actions.Action;
import engine.actions.ClickAction;
import engine.actions.FormAction;
import engine.actions.NavigationAction;
import engine.state.SessionState;
import org.openqa.selenium.WebDriver;
import utilities.ConfigReader;

import java.util.*;

public class ActionEngine {

    private final List<Action> actions = new ArrayList<>();

    private int maxCycles;
    private boolean stopOnError;

    public ActionEngine() {

        // 🔥 configurable order
        actions.add(new NavigationAction());
        actions.add(new ClickAction());
        actions.add(new FormAction());

        // 🔥 config
        this.maxCycles = ConfigReader.getInt("maxCycles", 2);
        this.stopOnError = false;
    }

    public void run(WebDriver driver) {

        System.out.println("⚡ [ActionEngine PRO] Start");

        int cycle = 0;

        while (cycle < maxCycles) {

            System.out.println("🔁 Cycle: " + (cycle + 1));

            for (Action action : actions) {

                try {

                    System.out.println("➡️ Running: " + action.getClass().getSimpleName());

                    action.execute(driver);

                } catch (Exception e) {

                    System.out.println("❌ Action failed: "
                            + action.getClass().getSimpleName());

                    if (stopOnError) return;
                }
            }

            cycle++;

            // 🔥 stop if nothing new happened
            if (isStable()) {
                System.out.println("🛑 System stabilized, stopping early");
                break;
            }
        }

        System.out.println("✅ [ActionEngine PRO] Finished");
    }

    // ===================================================
    // 🔥 SMART STOP CONDITION
    // ===================================================
    private boolean isStable() {

        int visitedUrls = SessionState.urlCount();
        int visitedElements = SessionState.elementCount();

        // لو مفيش زيادة في state → وقف
        return visitedUrls > 10 && visitedElements > 20;
    }
}