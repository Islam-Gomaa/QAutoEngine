package ai.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Plan {

    // ===================================================
    // 🧠 CORE
    // ===================================================
    private final String goal;

    private final List<String> steps =
            new ArrayList<>();

    private int currentStepIndex = 0;

    // ===================================================
    // 📊 META
    // ===================================================
    private double confidence = 1.0;
    private double stability = 1.0;

    private boolean completed;
    private boolean failed;

    private int failures;
    private int successes;

    // ===================================================
    public Plan(String goal) {

        this.goal =
                (goal == null || goal.isBlank())
                        ? "UNKNOWN"
                        : goal;
    }

    // ===================================================
    // 🧠 GETTERS
    // ===================================================
    public String getGoal() {
        return goal;
    }

    public double getConfidence() {
        return confidence;
    }

    public double getStability() {
        return stability;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isFailed() {
        return failed;
    }

    public int getFailures() {
        return failures;
    }

    public int getSuccesses() {
        return successes;
    }

    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    public int size() {
        return steps.size();
    }

    // ===================================================
    // 🔥 SAFE STEP ACCESS
    // ===================================================
    public List<String> getSteps() {

        return Collections.unmodifiableList(
                steps
        );
    }

    public String getCurrentStep() {

        if (steps.isEmpty()) {
            return null;
        }

        if (currentStepIndex >= steps.size()) {
            return null;
        }

        return steps.get(
                currentStepIndex
        );
    }

    public String peekNextStep() {

        int next =
                currentStepIndex + 1;

        if (next >= steps.size()) {
            return null;
        }

        return steps.get(next);
    }

    // ===================================================
    // 🔥 STEP CONTROL
    // ===================================================
    public void next() {

        successes++;

        currentStepIndex++;

        if (currentStepIndex >=
                steps.size()) {

            completed = true;
        }

        // small confidence boost
        confidence =
                Math.min(
                        5,
                        confidence + 0.2
                );
    }

    public void fail() {

        failures++;

        confidence =
                Math.max(
                        0,
                        confidence - 0.5
                );

        stability =
                Math.max(
                        0,
                        stability - 0.4
                );

        if (failures >= 3) {
            failed = true;
        }
    }

    public void success() {

        successes++;

        confidence =
                Math.min(
                        5,
                        confidence + 0.3
                );

        stability =
                Math.min(
                        5,
                        stability + 0.2
                );
    }

    // ===================================================
    // 🔥 STEP MANAGEMENT
    // ===================================================
    public void addStep(String step) {

        if (step == null
                || step.isBlank()) {
            return;
        }

        steps.add(step);
    }

    public void addSteps(
            List<String> newSteps) {

        if (newSteps == null
                || newSteps.isEmpty()) {
            return;
        }

        for (String step
                : newSteps) {

            addStep(step);
        }
    }

    public void resetSteps() {

        steps.clear();
        currentStepIndex = 0;

        completed = false;
        failed = false;
    }

    // ===================================================
    // 📊 HEALTH SCORE
    // ===================================================
    public double healthScore() {

        double progress =
                steps.isEmpty()
                        ? 0
                        : (double)
                        currentStepIndex
                        / steps.size();

        double score =
                confidence * 0.35
                        + stability * 0.30
                        + progress * 0.35
                        - failures * 0.50;

        return clamp(score, -5, 10);
    }

    // ===================================================
    // 🔧 HELPERS
    // ===================================================
    private double clamp(
            double v,
            double min,
            double max) {

        return Math.max(
                min,
                Math.min(max, v)
        );
    }

    // ===================================================
    // 🧠 DEBUG
    // ===================================================
    @Override
    public String toString() {

        return "Plan{" +
                "goal='" + goal + '\'' +
                ", step=" +
                currentStepIndex +
                "/" + steps.size() +
                ", current='" +
                getCurrentStep() +
                '\'' +
                ", confidence=" +
                confidence +
                ", stability=" +
                stability +
                ", failures=" +
                failures +
                ", completed=" +
                completed +
                ", failed=" +
                failed +
                '}';
    }
}