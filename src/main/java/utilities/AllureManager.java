package utilities;

import java.io.File;
import java.io.IOException;

public class AllureManager {

    // ===================================================
    // 🧹 CLEAN
    // ===================================================
    public static void cleanResults() {
        System.out.println("🧹 Cleaning Allure folders...");
        deleteFolder(new File("allure-results"));
        deleteFolder(new File("allure-report"));
    }

    private static void deleteFolder(File folder) {

        if (!folder.exists()) return;

        File[] files = folder.listFiles();

        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteFolder(f);
                } else {
                    if (!f.delete()) {
                        System.out.println("⚠️ Failed to delete file: " + f.getAbsolutePath());
                    }
                }
            }
        }

        if (!folder.delete()) {
            System.out.println("⚠️ Failed to delete folder: " + folder.getAbsolutePath());
        }
    }

    // ===================================================
    // 📊 GENERATE REPORT
    // ===================================================
    public static void generateReport() {

        try {
            System.out.println("📊 Generating Allure report...");

            ProcessBuilder generate = new ProcessBuilder(
                    "allure", "generate", "allure-results", "--clean", "-o", "allure-report"
            );

            generate.inheritIO();

            Process process = generate.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println("✅ Allure report generated successfully");
            } else {
                System.out.println("❌ Allure generate failed");
            }

        } catch (IOException e) {
            System.out.println("❌ Allure not installed or not in PATH");
        } catch (Exception e) {
            System.out.println("❌ Error generating report: " + e.getMessage());
        }
    }

    // ===================================================
    // 🚀 OPEN REPORT
    // ===================================================
    public static void openReport() {

        try {
            System.out.println("🚀 Opening Allure report...");

            ProcessBuilder open = new ProcessBuilder(
                    "allure", "open", "allure-report"
            );

            open.inheritIO();
            open.start();

        } catch (IOException e) {
            System.out.println("❌ Failed to open Allure (check installation)");
        }
    }

    // ===================================================
    // 🔥 FULL FLOW
    // ===================================================
    public static void generateAndOpenReport() {

        generateReport();
        openReport();
    }
}