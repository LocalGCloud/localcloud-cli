package com.localcloud.emulators.workflows.engine;

/**
 * Thrown by NextStep to jump to a named step.
 */
public class NextStepException extends RuntimeException {
    private final String targetStep;

    public NextStepException(String targetStep) {
        super("next: " + targetStep);
        this.targetStep = targetStep;
    }

    public String getTargetStep() { return targetStep; }
}
