package com.yupi.aicodehelper.agent.eval;

import com.yupi.aicodehelper.agent.core.AgentToolRegistry;

import java.util.List;

public record AgentEvalScenario(
        String name,
        String description,
        String userGoal,
        AgentToolRegistry toolRegistry,
        AgentEvalRubric rubric,
        List<String> expectedToolSequence,
        String expectedAnswer,
        int minExpectedTurns,
        boolean expectPlan,
        int maxTurns
) {
    public AgentEvalScenario {
        if (expectedToolSequence == null) {
            expectedToolSequence = List.of();
        }
        if (expectedAnswer == null) {
            expectedAnswer = "";
        }
        if (minExpectedTurns < 1) {
            minExpectedTurns = 1;
        }
    }

    public AgentEvalScenario(String name, String description, String userGoal,
                              AgentToolRegistry toolRegistry, AgentEvalRubric rubric) {
        this(name, description, userGoal, toolRegistry, rubric, List.of(), "", 1, false, 8);
    }
}
