package com.yupi.aicodehelper.agent.eval;

import com.yupi.aicodehelper.agent.core.AgentHookEvent;
import com.yupi.aicodehelper.agent.core.AgentLoopState;

import java.util.List;

public record AgentEvalResult(
        AgentEvalScenario scenario,
        AgentEvalScore score,
        List<AgentHookEvent> capturedEvents,
        List<String> actualToolCalls,
        List<String> actualRecoveries,
        int actualTurns,
        boolean hasPlan,
        List<String> planStepsUsed,
        AgentLoopState finalState,
        boolean passed
) {
    public String summary() {
        return "[%s] composite=%.2f answer=%.2f tool=%.2f efficiency=%.2f robustness=%.2f plan=%.2f turns=%d %s"
                .formatted(scenario.name(), score.composite(),
                        score.answerCorrectness(), score.toolSelectionAccuracy(),
                        score.turnEfficiency(), score.robustness(), score.planAdherence(),
                        actualTurns, passed ? "PASS" : "FAIL");
    }
}
