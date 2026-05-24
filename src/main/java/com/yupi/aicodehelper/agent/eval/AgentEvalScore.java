package com.yupi.aicodehelper.agent.eval;

public record AgentEvalScore(
        double answerCorrectness,
        double toolSelectionAccuracy,
        double turnEfficiency,
        double robustness,
        double planAdherence,
        double composite
) {
    public static AgentEvalScore fromDimensions(AgentEvalRubric rubric,
                                                 double answerCorrectness,
                                                 double toolSelectionAccuracy,
                                                 double turnEfficiency,
                                                 double robustness,
                                                 double planAdherence) {
        double composite = answerCorrectness * rubric.answerCorrectnessWeight()
                + toolSelectionAccuracy * rubric.toolSelectionAccuracyWeight()
                + turnEfficiency * rubric.turnEfficiencyWeight()
                + robustness * rubric.robustnessWeight()
                + planAdherence * rubric.planAdherenceWeight();
        return new AgentEvalScore(
                clamp01(answerCorrectness),
                clamp01(toolSelectionAccuracy),
                clamp01(turnEfficiency),
                clamp01(robustness),
                clamp01(planAdherence),
                clamp01(composite)
        );
    }

    public boolean passed(double threshold) {
        return composite >= threshold;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
