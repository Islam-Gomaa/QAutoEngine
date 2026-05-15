package ai.planning;

import java.util.*;

public class Plan {

    // ===================================================
    // 🧠 CORE
    // ===================================================
    public final String goal;

    private final List<String> steps = new ArrayList<>();
    private int currentStepIndex = 0;

    // ===================================================
    // 📊 META
    // ===================================================
    public double confidence;
    public double stability;

    public boolean completed;
    public boolean failed;

    private int failures;
    private int successes;

    // ===================================================
    public Plan(String goal) {
        this.goal = goal;
    }

    // ===================================================
    // 🔥 STEP ACCESS
    // ===================================================
    public String getCurrentStep() {

        if (currentStepIndex >= steps.size()) return null;

        return steps.get(currentStepIndex);
    }

    public String peekNextStep() {

        if (currentStepIndex + 1 >= steps.size()) return null;

        return steps.get(currentStepIndex + 1);
    }

    // ===================================================
    // 🔥 STEP CONTROL
    // ===================================================
    public void next() {

        successes++;

        currentStepIndex++;

        if (currentStepIndex >= steps.size()) {
            completed = true;
        }
    }

    public void fail() {

        failures++;

        // 🔥 adaptive failure logic
        if (failures >= 3) {
            failed = true;
        }

        // 🔥 stability drop
        stability -= 0.5;
    }

    public void success() {

        successes++;

        // 🔥 boost confidence
        confidence += 0.3;

        stability += 0.2;
    }

    // ===================================================
    // 🔥 STEP MANAGEMENT
    // ===================================================
    public void addStep(String step) {

        if (step == null) return;

        steps.add(step);
    }

    public void addSteps(List<String> newSteps) {

        if (newSteps == null) return;

        for (String s : newSteps) {
            addStep(s);
        }
    }

    public void resetSteps() {
        steps.clear();
        currentStepIndex = 0;
    }

    public int size() {
        return steps.size();
    }

    // ===================================================
    // 🔥 HEALTH SCORE (Decision + Planning)
    // ===================================================
    public double healthScore() {

        double progress = (double) currentStepIndex / Math.max(1, steps.size());

        return confidence * 0.4
                + stability * 0.3
                + progress * 0.3
                - failures * 0.5;
    }

    // ===================================================
    // 🔥 DEBUG
    // ===================================================
    @Override
    public String toString() {

        return "Plan{" +
                "goal='" + goal + '\'' +
                ", step=" + currentStepIndex + "/" + steps.size() +
                ", current='" + getCurrentStep() + '\'' +
                ", confidence=" + confidence +
                ", stability=" + stability +
                ", failures=" + failures +
                ", completed=" + completed +
                ", failed=" + failed +
                '}';
    }
}