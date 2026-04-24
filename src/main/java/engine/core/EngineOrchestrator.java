package engine.core;

import engine.actions.Action;
import engine.actions.ClickAction;
import engine.actions.FormAction;
import engine.actions.NavigationAction;
import org.openqa.selenium.WebDriver;

import java.util.ArrayList;
import java.util.List;

public class EngineOrchestrator {

    private final List<Action> actions = new ArrayList<>();
    private final ScannerEngine scannerEngine;

    // ⚙️ Configurable
    private final int cycles = 2;
    private final int maxFailures = 5;

    public EngineOrchestrator() {

        this.scannerEngine = new ScannerEngine();

        // 🔥 ترتيب execution مهم
        actions.add(new NavigationAction());
        actions.add(new ClickAction());
        actions.add(new FormAction());
    }

    public void run(WebDriver driver) {

        System.out.println("🚀 Engine Orchestrator START");

        int failures = 0;

        try {

            // ==============================
            // 🔥 PHASE 1: SCAN
            // ==============================
            log("🔍 Running Scanner Engine...");
            safeScan(driver);

            // ==============================
            // 🔥 PHASE 2: ACTIONS LOOP
            // ==============================
            for (int i = 0; i < cycles; i++) {

                log("🔁 Cycle: " + (i + 1));

                for (Action action : actions) {

                    boolean success = executeSafely(driver, action);

                    if (!success) {
                        failures++;

                        if (failures >= maxFailures) {
                            log("🛑 Too many failures, stopping...");
                            return;
                        }
                    }
                }
            }

        } catch (Exception e) {

            log("❌ Orchestrator crashed: " + e.getMessage());

        } finally {

            log("✅ Engine Orchestrator END");
        }
    }

    // ===================================================
    // 🔍 SAFE SCAN
    // ===================================================
    private void safeScan(WebDriver driver) {

        try {

            // 👇 استخدم واحدة من دول حسب ScannerEngine عندك
            scannerEngine.runAndCollect(driver);
            // scannerEngine.run(driver);

        } catch (Exception e) {

            log("❌ Scanner failed: " + e.getMessage());
        }
    }

    // ===================================================
    // 🔐 SAFE EXECUTION
    // ===================================================
    private boolean executeSafely(WebDriver driver, Action action) {

        String name = action.getClass().getSimpleName();

        try {

            log("⚙️ Running: " + name);

            action.execute(driver);

            return true;

        } catch (Exception e) {

            log("❌ Failed: " + name);

            recover(driver);

            return false;
        }
    }

    // ===================================================
    // 🧯 RECOVERY SYSTEM (SMART)
    // ===================================================
    private void recover(WebDriver driver) {

        try {

            String current = driver.getCurrentUrl();

            log("🧯 Recovering from: " + current);

            // 🔥 block external redirects
            if (current.contains("whatsapp") ||
                    current.contains("instagram") ||
                    current.contains("facebook")) {

                driver.navigate().back();
                return;
            }

            // 🔥 normal recovery
            driver.navigate().back();

        } catch (Exception ignored) {}
    }

    // ===================================================
    // 🧾 LOGGER
    // ===================================================
    private void log(String msg) {

        System.out.println(msg);
    }
}