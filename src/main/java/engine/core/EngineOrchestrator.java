package engine.core;

import engine.actions.*;
import engine.state.SessionState;
import org.openqa.selenium.WebDriver;

import java.util.ArrayList;
import java.util.List;

public class EngineOrchestrator {

    private final List<Action> actions = new ArrayList<>();
    private final ScannerEngine scannerEngine;

    private final int maxSteps = 20;     // 🔥 بدل cycles
    private final int maxFailures = 5;

    public EngineOrchestrator() {

        this.scannerEngine = new ScannerEngine();

        actions.add(new NavigationAction());
        actions.add(new ClickAction());
        actions.add(new FormAction());
    }

    public void run(WebDriver driver) {

        System.out.println("🚀 Engine Orchestrator START");

        int failures = 0;

        try {

            // ==============================
            // 🔍 INITIAL SCAN
            // ==============================
            log("🔍 Initial Scan...");
            safeScan(driver);

            // ==============================
            // 🔥 AI LOOP
            // ==============================
            for (int step = 0; step < maxSteps; step++) {

                log("\n🧠 STEP: " + (step + 1));

                boolean anySuccess = false;

                for (Action action : actions) {

                    boolean success = executeSafely(driver, action);

                    if (success) {
                        anySuccess = true;

                        // 🔥 re-scan after success
                        safeScan(driver);

                        break; // 🔥 نفذ Action واحد ذكي بس
                    } else {
                        failures++;
                    }

                    if (failures >= maxFailures) {
                        log("🛑 Too many failures, stopping...");
                        return;
                    }
                }

                // 💀 لو مفيش أي حاجة نجحت → stop
                if (!anySuccess) {
                    log("⚠️ No progress detected, stopping...");
                    break;
                }

                // 🧠 Smart stop condition
                if (isStable()) {
                    log("🧠 System stabilized, stopping...");
                    break;
                }
            }

        } catch (Exception e) {

            log("❌ Orchestrator crashed: " + e.getMessage());

        } finally {

            log("✅ Engine Orchestrator END");
        }
    }

    // ===================================================
    // 🔍 SCAN
    // ===================================================
    private void safeScan(WebDriver driver) {

        try {
            scannerEngine.runAndCollect(driver);
        } catch (Exception e) {
            log("❌ Scanner failed");
        }
    }

    // ===================================================
    // 🔐 EXECUTION
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
    // 🧯 RECOVERY
    // ===================================================
    private void recover(WebDriver driver) {

        try {

            driver.navigate().back();

        } catch (Exception ignored) {}
    }

    // ===================================================
    // 🧠 STOP CONDITION
    // ===================================================
    private boolean isStable() {

        int urls = SessionState.urlCount();
        int elements = SessionState.elementCount(); // 👈 لازم تكون عاملها

        return urls > 10 && elements > 30;
    }

    // ===================================================
    private void log(String msg) {
        System.out.println(msg);
    }
}