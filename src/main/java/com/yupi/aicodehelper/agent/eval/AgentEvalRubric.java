package com.yupi.aicodehelper.agent.eval;

public record AgentEvalRubric(
        double answerCorrectnessWeight,
        double toolSelectionAccuracyWeight,
        double turnEfficiencyWeight,
        double robustnessWeight,
        double planAdherenceWeight
) {
    public AgentEvalRubric {
        double sum = answerCorrectnessWeight + toolSelectionAccuracyWeight
                + turnEfficiencyWeight + robustnessWeight + planAdherenceWeight;
        if (Math.abs(sum - 1.0) > 0.001) {
            throw new IllegalArgumentException("Rubric weights must sum to 1.0, got " + sum);
        }
        if (answerCorrectnessWeight < 0 || toolSelectionAccuracyWeight < 0
                || turnEfficiencyWeight < 0 || robustnessWeight < 0 || planAdherenceWeight < 0) {
            throw new IllegalArgumentException("Rubric weights must be non-negative");
        }
    }

    public static AgentEvalRubric balanced() {
        return new AgentEvalRubric(0.30, 0.25, 0.15, 0.15, 0.15);
    }

    public static AgentEvalRubric answerFocused() {
        return new AgentEvalRubric(0.60, 0.10, 0.10, 0.10, 0.10);
    }

    public static AgentEvalRubric toolFocused() {
        return new AgentEvalRubric(0.10, 0.50, 0.10, 0.20, 0.10);
    }

    public static AgentEvalRubric efficiencyFocused() {
        return new AgentEvalRubric(0.20, 0.15, 0.40, 0.10, 0.15);
    }
}
