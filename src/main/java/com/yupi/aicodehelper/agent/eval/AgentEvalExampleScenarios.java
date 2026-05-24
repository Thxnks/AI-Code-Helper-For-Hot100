package com.yupi.aicodehelper.agent.eval;

import com.yupi.aicodehelper.agent.core.AgentToolRegistry;

import java.util.List;
import java.util.Map;

public final class AgentEvalExampleScenarios {

    private AgentEvalExampleScenarios() {}

    public static List<AgentEvalScenario> builtInScenarios() {
        return List.of(
                perfectToolChain(),
                recoveryFromUnknownTool(),
                maxTurnsReached(),
                multiStepWithPlan(),
                multiToolRecommendation()
        );
    }

    public static AgentEvalScenario perfectToolChain() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getWeakTags", "Return the user's weak tags.",
                        input -> Map.of("tags", List.of("dp", "binary-search")))
                .register("recommendNext", "Recommend next problem.",
                        input -> "two-sum");

        return new AgentEvalScenario(
                "perfect-tool-chain",
                "Agent calls two tools in sequence then answers with recommended problem",
                "Recommend my next problem based on weak tags.",
                registry,
                AgentEvalRubric.balanced(),
                List.of("getWeakTags", "recommendNext"),
                "two-sum",
                3, true, 5
        );
    }

    public static AgentEvalScenario recoveryFromUnknownTool() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getWeakTags", "Return the user's weak tags.",
                        input -> Map.of("tags", List.of("dp")));

        return new AgentEvalScenario(
                "recovery-from-unknown-tool",
                "Agent tries unknown tool, recovers, then calls correct tool",
                "Check my weak tags.",
                registry,
                AgentEvalRubric.balanced(),
                List.of("getWeakTags"),
                "dp",
                2, false, 5
        );
    }

    public static AgentEvalScenario maxTurnsReached() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("observe", "Observe some data.", input -> "data");

        return new AgentEvalScenario(
                "max-turns-reached",
                "Agent loops on tool until max turns, forced terminal answer",
                "Keep observing until I say stop.",
                registry,
                AgentEvalRubric.efficiencyFocused(),
                List.of("observe"),
                "turns",
                1, false, 2
        );
    }

    public static AgentEvalScenario multiStepWithPlan() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getWeakTags", "Return weak tags.",
                        input -> Map.of("tags", List.of("dp", "graph")))
                .register("listProblems", "List problems by tag.",
                        input -> Map.of("problems", List.of("climb-stairs", "course-schedule")))
                .register("filterByDifficulty", "Filter problems by difficulty.",
                        input -> Map.of("filtered", List.of("climb-stairs")));

        return new AgentEvalScenario(
                "multi-step-with-plan",
                "Agent generates and follows a 3-step plan to find an easy problem",
                "Find me an easy DP problem to practice.",
                registry,
                AgentEvalRubric.balanced(),
                List.of("getWeakTags", "listProblems", "filterByDifficulty"),
                "climb-stairs",
                4, true, 8
        );
    }

    public static AgentEvalScenario multiToolRecommendation() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getTagMastery", "Get tag mastery stats.",
                        input -> Map.of("mastery", List.of(
                                Map.of("tag", "dp", "masteryRate", 0.4),
                                Map.of("tag", "graph", "masteryRate", 0.8))))
                .register("searchProblems", "Search problems by criteria.",
                        input -> Map.of("results", List.of("coin-change", "edit-distance")));

        return new AgentEvalScenario(
                "multi-tool-recommendation",
                "Agent uses two tools to analyze mastery and find problems",
                "What should I study next based on my tag mastery?",
                registry,
                AgentEvalRubric.toolFocused(),
                List.of("getTagMastery", "searchProblems"),
                "coin-change",
                3, false, 5
        );
    }
}
