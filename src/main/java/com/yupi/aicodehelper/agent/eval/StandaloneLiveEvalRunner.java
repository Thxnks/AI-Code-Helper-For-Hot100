package com.yupi.aicodehelper.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.aicodehelper.agent.core.AgentLoopService;
import com.yupi.aicodehelper.agent.core.AgentToolRegistry;
import com.yupi.aicodehelper.agent.core.AgentTurnClient;
import dev.langchain4j.community.model.dashscope.QwenChatModel;

import java.util.List;
import java.util.Map;

/**
 * Standalone live eval runner — no Spring context, no MySQL, no Redis.
 * Just needs DASHSCOPE_API_KEY env var set.
 *
 * Run: .\mvnw.cmd exec:java -Dexec.mainClass="com.yupi.aicodehelper.agent.eval.StandaloneLiveEvalRunner"
 */
public final class StandaloneLiveEvalRunner {

    private StandaloneLiveEvalRunner() {}

    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("DASHSCOPE_API_KEY env var is not set. Set it and retry.");
            System.exit(1);
        }

        QwenChatModel chatModel = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-max")
                .build();

        AgentTurnClient turnClient = prompt -> {
            int len = prompt.length();
            System.out.println("  [→ model, prompt " + len + " chars]");
            String response = chatModel.chat(prompt);
            String preview = response.length() > 120 ? response.substring(0, 120) + "..." : response;
            System.out.println("  [← model, " + response.length() + " chars] " + preview);
            return response;
        };

        ObjectMapper objectMapper = new ObjectMapper();
        AgentLoopService loopService = new AgentLoopService(objectMapper);

        AgentEvaluator evaluator = new AgentEvaluator(loopService, turnClient);

        List<AgentEvalScenario> scenarios = liveScenarios();

        System.out.println("\n=== Agent Live Evaluation (qwen-max, standalone) ===\n");

        for (AgentEvalScenario scenario : scenarios) {
            System.out.println("Running: " + scenario.name() + " — " + scenario.description());
            AgentEvalResult result = evaluator.evaluateLive(scenario);
            System.out.println("  " + result.summary());
        }

        System.out.println("\nAll live scenarios complete.\n");
    }

    private static List<AgentEvalScenario> liveScenarios() {
        return List.of(
                toolReasoningScenario(),
                multiStepScenario(),
                recoveryScenario()
        );
    }

    private static AgentEvalScenario toolReasoningScenario() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getTagMastery", "Get per-tag mastery stats (practiced, mastered, wrong, rate).",
                        input -> Map.of(
                                "tags", List.of(
                                        Map.of("tag", "array", "masteryRate", 0.9, "practicedCount", 12),
                                        Map.of("tag", "dynamic-programming", "masteryRate", 0.3, "practicedCount", 4),
                                        Map.of("tag", "graph", "masteryRate", 0.5, "practicedCount", 6)
                                )));

        return new AgentEvalScenario(
                "tool-reasoning",
                "Agent inspects tag mastery data and identifies the weakest area",
                "Analyze my tag mastery data and tell me which algorithm category I should focus on most.",
                registry,
                AgentEvalRubric.answerFocused(),
                List.of("getTagMastery"),
                "dynamic-programming",
                2, false, 5
        );
    }

    private static AgentEvalScenario multiStepScenario() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getWeakTags", "Return the user's weak tags with error counts.",
                        input -> Map.of("weakTags", List.of(
                                Map.of("tag", "backtracking", "wrongCount", 5),
                                Map.of("tag", "binary-search", "wrongCount", 2))))
                .register("searchProblems", "Search problems by tag and difficulty.",
                        input -> {
                            String tag = String.valueOf(input.getOrDefault("tag", ""));
                            if (tag.contains("backtracking")) {
                                return Map.of("problems", List.of("permutations", "subsets", "n-queens"));
                            }
                            return Map.of("problems", List.of());
                        });

        return new AgentEvalScenario(
                "multi-step-reasoning",
                "Agent checks weak tags then searches for relevant practice problems",
                "I keep failing backtracking problems. Find me some backtracking problems to practice and recommend which one to start with.",
                registry,
                AgentEvalRubric.balanced(),
                List.of("getWeakTags", "searchProblems"),
                "permutations",
                3, false, 6
        );
    }

    private static AgentEvalScenario recoveryScenario() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("listProblems", "List problems. Input: tag (required).",
                        input -> {
                            String tag = String.valueOf(input.getOrDefault("tag", ""));
                            if (tag.isBlank()) {
                                throw new IllegalArgumentException("tag is required for listProblems");
                            }
                            return Map.of("problems", List.of("two-sum", "three-sum"));
                        });

        return new AgentEvalScenario(
                "parameter-recovery",
                "Agent calls tool with missing parameter, recovers, then succeeds",
                "List some array problems for me.",
                registry,
                AgentEvalRubric.toolFocused(),
                List.of("listProblems"),
                "two-sum",
                2, false, 5
        );
    }
}
