package com.yupi.aicodehelper.agent.eval;

import com.yupi.aicodehelper.ai.AiCodeHelperService;
import com.yupi.aicodehelper.agent.core.AgentLoopService;
import com.yupi.aicodehelper.agent.core.AgentToolRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Profile("eval")
public class LiveAgentEvalRunner implements CommandLineRunner {

    private final AgentEvaluator evaluator;

    public LiveAgentEvalRunner(AgentLoopService agentLoopService,
                                AiCodeHelperService aiCodeHelperService) {
        this.evaluator = new AgentEvaluator(
                agentLoopService, aiCodeHelperService::runHot100AgentLoopTurn);
    }

    @Override
    public void run(String... args) {
        System.out.println("\n=== Agent Live Evaluation (real LLM) ===\n");

        for (AgentEvalScenario scenario : liveScenarios()) {
            System.out.println("Running: " + scenario.name() + " ...");
            AgentEvalResult result = evaluator.evaluateLive(scenario);
            System.out.println("  " + result.summary());
        }

        System.out.println("\nAll live scenarios complete.\n");
    }

    private List<AgentEvalScenario> liveScenarios() {
        return List.of(
                toolReasoningScenario(),
                multiStepScenario(),
                recoveryScenario()
        );
    }

    private AgentEvalScenario toolReasoningScenario() {
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

    private AgentEvalScenario multiStepScenario() {
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

    private AgentEvalScenario recoveryScenario() {
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
