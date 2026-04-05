package io.github.fujianyang.stepengine.outcome;

public final class StepSuccess implements StepOutcome {
    static final StepSuccess INSTANCE = new StepSuccess();

    private StepSuccess() {
    }

    @Override
    public String toString() {
        return "SUCCESS";
    }
}