package com.yupi.aicodehelper.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.aicodehelper.agent.core.AgentHookEvent;
import com.yupi.aicodehelper.agent.core.AgentHookEventType;
import com.yupi.aicodehelper.agent.core.AgentToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEvaluatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentEvaluator evaluator = new AgentEvaluator(objectMapper);

    @Test
    void shouldScorePerfectToolChain() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getWeakTags", "Return weak tags.",
                        input -> Map.of("tags", List.of("dp", "binary-search")))
                .register("recommendNext", "Recommend next problem.",
                        input -> "two-sum");

        // maxTurns=5 (< 8) skips plan stage, tool simulation maps directly
        AgentEvalScenario scenario = new AgentEvalScenario(
                "perfect-tool-chain", "Agent calls two tools then answers",
                "Recommend my next problem based on weak tags.",
                registry, AgentEvalRubric.balanced(),
                List.of("getWeakTags", "recommendNext"), "two-sum", 2, false, 5
        );

        AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
        sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("getWeakTags", Map.of()));
        sim.whenTurn(2).respondWith(AgentEvalModelSimulator.toolUse("recommendNext", Map.of("limit", 1)));
        sim.whenTurn(3).respondWith(AgentEvalModelSimulator.finalAnswer("I recommend two-sum for DP practice."));

        AgentEvalResult result = evaluator.evaluate(scenario, sim);

        assertThat(result.passed()).isTrue();
        assertThat(result.score().answerCorrectness()).isGreaterThanOrEqualTo(0.8);
        assertThat(result.score().toolSelectionAccuracy()).isEqualTo(1.0);
        assertThat(result.score().robustness()).isEqualTo(1.0);
        assertThat(result.score().composite()).isGreaterThan(0.70);
        assertThat(result.actualToolCalls()).containsExactly("getWeakTags", "recommendNext");
    }

    @Test
    void shouldDetectWrongToolOrder() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getWeakTags", "Return weak tags.",
                        input -> Map.of("tags", List.of("dp")))
                .register("recommendNext", "Recommend next.",
                        input -> "two-sum");

        AgentEvalScenario scenario = new AgentEvalScenario(
                "wrong-order", "Agent calls tools in wrong order",
                "Recommend next based on weak tags.",
                registry, AgentEvalRubric.toolFocused(),
                List.of("getWeakTags", "recommendNext"), "two-sum", 3, false, 5
        );

        AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
        // Wrong order: recommendNext first, then getWeakTags
        sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("recommendNext", Map.of()));
        sim.whenTurn(2).respondWith(AgentEvalModelSimulator.toolUse("getWeakTags", Map.of()));
        sim.whenTurn(3).respondWith(AgentEvalModelSimulator.finalAnswer("I recommend two-sum."));

        AgentEvalResult result = evaluator.evaluate(scenario, sim);

        // LCS of [getWeakTags, recommendNext] vs [recommendNext, getWeakTags]:
        // LCS = 1 (one element can match), max = 2, score = 0.5
        assertThat(result.score().toolSelectionAccuracy()).isEqualTo(0.5);
    }

    @Test
    void shouldPenalizeRecovery() {
        AgentEvalScenario scenario = AgentEvalExampleScenarios.recoveryFromUnknownTool();
        AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
        // Turn 1: try unknown tool → triggers recovery
        sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("badTool", Map.of()));
        // Turn 2: after recovery, try correct tool
        sim.whenTurn(2).respondWith(AgentEvalModelSimulator.toolUse("getWeakTags", Map.of()));
        sim.whenTurn(3).respondWith(AgentEvalModelSimulator.finalAnswer("Your weak tag is dp."));

        AgentEvalResult result = evaluator.evaluate(scenario, sim);

        assertThat(result.score().robustness()).isLessThanOrEqualTo(0.8);
        assertThat(result.actualRecoveries()).isNotEmpty();
        assertThat(result.actualToolCalls()).contains("getWeakTags");
    }

    @Test
    void shouldDetectMaxTurnsReached() {
        AgentEvalScenario scenario = AgentEvalExampleScenarios.maxTurnsReached();
        AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
        // Loop on observe until maxTurns=2 forces stop
        sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("observe", Map.of()));
        sim.whenTurn(2).respondWith(AgentEvalModelSimulator.toolUse("observe", Map.of()));
        sim.fallback(AgentEvalModelSimulator.toolUse("observe", Map.of()));

        AgentEvalResult result = evaluator.evaluate(scenario, sim);

        assertThat(result.score().robustness()).isEqualTo(0.5);
        assertThat(result.score().turnEfficiency()).isEqualTo(0.5);
        assertThat(result.actualTurns()).isEqualTo(2);
    }

    @Test
    void shouldScoreFullPlanAdherence() {
        AgentEvalScenario scenario = AgentEvalExampleScenarios.multiStepWithPlan();
        AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
        sim.whenTurn(1).respondWith(AgentEvalModelSimulator.plan(List.of(
                step("Check weak tags", "getWeakTags", "Identify weak areas", 0),
                step("List problems", "listProblems", "Find DP problems", 1),
                step("Filter by difficulty", "filterByDifficulty", "Keep only easy", 2)
        )));
        sim.whenTurn(2).respondWith(AgentEvalModelSimulator.toolUse("getWeakTags", Map.of()));
        sim.whenTurn(3).respondWith(AgentEvalModelSimulator.toolUse("listProblems", Map.of("tag", "dp")));
        sim.whenTurn(4).respondWith(AgentEvalModelSimulator.toolUse("filterByDifficulty", Map.of("difficulty", "easy")));
        sim.whenTurn(5).respondWith(AgentEvalModelSimulator.finalAnswer("I recommend climb-stairs."));

        AgentEvalResult result = evaluator.evaluate(scenario, sim);

        assertThat(result.hasPlan()).isTrue();
        assertThat(result.score().planAdherence()).isEqualTo(1.0);
        assertThat(result.planStepsUsed()).containsExactly("getWeakTags", "listProblems", "filterByDifficulty");
    }

    @Test
    void shouldScorePartialPlanAdherence() {
        AgentEvalScenario scenario = AgentEvalExampleScenarios.multiStepWithPlan();
        AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
        sim.whenTurn(1).respondWith(AgentEvalModelSimulator.plan(List.of(
                step("Check weak tags", "getWeakTags", "Identify weak areas", 0),
                step("List problems", "listProblems", "Find DP problems", 1),
                step("Filter by difficulty", "filterByDifficulty", "Keep only easy", 2)
        )));
        // Only execute 2 out of 3 planned tools
        sim.whenTurn(2).respondWith(AgentEvalModelSimulator.toolUse("getWeakTags", Map.of()));
        sim.whenTurn(3).respondWith(AgentEvalModelSimulator.toolUse("listProblems", Map.of("tag", "dp")));
        // Skip filterByDifficulty, go straight to answer
        sim.whenTurn(4).respondWith(AgentEvalModelSimulator.finalAnswer("Try climb-stairs or course-schedule."));

        AgentEvalResult result = evaluator.evaluate(scenario, sim);

        // 2 out of 3 planned tools executed → 0.66
        assertThat(result.score().planAdherence()).isEqualTo(2.0 / 3.0);
    }

    @Test
    void shouldProduceReportWithCorrectAggregates() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("data", "Get data.", input -> "ok");

        AgentEvalScenario s1 = new AgentEvalScenario("s1", "desc", "goal", registry,
                AgentEvalRubric.balanced(), List.of("data"), "ok", 1, false, 5);
        AgentEvalScenario s2 = new AgentEvalScenario("s2", "desc", "goal", registry,
                AgentEvalRubric.balanced(), List.of("data"), "ok", 1, false, 5);

        AgentEvalReport report = evaluator.evaluateAll(List.of(s1, s2), scenario -> {
            AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
            sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("data", Map.of()));
            sim.whenTurn(2).respondWith(AgentEvalModelSimulator.finalAnswer("ok"));
            return sim;
        });

        assertThat(report.results()).hasSize(2);
        assertThat(report.passCount()).isEqualTo(2);
        assertThat(report.passRate()).isEqualTo(1.0);
        assertThat(report.averageComposite()).isGreaterThan(0.75);
        String formatted = report.format();
        assertThat(formatted).contains("=== Agent Evaluation Report ===");
        assertThat(formatted).contains("PASS");
        assertThat(formatted).contains("Aggregate");
    }

    @Test
    void shouldHandleExceptionDuringRun() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("data", "Get data.", input -> { throw new RuntimeException("boom"); });

        AgentEvalScenario scenario = new AgentEvalScenario(
                "crash", "Tool throws exception", "goal", registry, AgentEvalRubric.balanced()
        );

        AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
        sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("data", Map.of()));

        AgentEvalResult result = evaluator.evaluate(scenario, sim);

        assertThat(result.passed()).isFalse();
        assertThat(result.score().composite()).isEqualTo(0.0);
    }

    @Test
    void shouldWorkWithoutSpringContext() {
        AgentEvaluator localEvaluator = new AgentEvaluator(new ObjectMapper());
        assertThat(localEvaluator).isNotNull();

        AgentToolRegistry registry = new AgentToolRegistry()
                .register("ping", "Ping tool.", input -> "pong");
        AgentEvalScenario scenario = new AgentEvalScenario(
                "no-spring", "Pure Java test", "ping", registry, AgentEvalRubric.answerFocused(),
                List.of("ping"), "pong", 2, false, 5
        );

        AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
        sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("ping", Map.of()));
        sim.whenTurn(2).respondWith(AgentEvalModelSimulator.finalAnswer("pong"));

        AgentEvalResult result = localEvaluator.evaluate(scenario, sim);
        assertThat(result.score().composite()).isGreaterThan(0.8);
    }

    @Test
    void shouldRejectRubricWeightsNotSummingToOne() {
        assertThatThrownBy(() -> new AgentEvalRubric(0.5, 0.5, 0.5, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to 1.0");
    }

    @Test
    void shouldRejectNegativeWeights() {
        assertThatThrownBy(() -> new AgentEvalRubric(-0.1, 0.5, 0.3, 0.2, 0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void shouldUseConditionalPromptMatch() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getData", "Get data.", input -> Map.of("value", 42));

        AgentEvalScenario scenario = new AgentEvalScenario(
                "conditional-match", "Simulator matches prompt content",
                "Get some data for me.", registry, AgentEvalRubric.balanced(),
                List.of("getData"), "42", 2, false, 5
        );

        AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
        sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("getData", Map.of()));
        sim.whenTurn(2).respondWith(AgentEvalModelSimulator.finalAnswer("The value is 42."));
        // Conditional rule for compaction prompt (should only fire if compaction triggers)
        sim.whenPromptMatches(
                prompt -> prompt.contains("Summarize this agent conversation"),
                "{\"goal\":\"Get data\",\"done\":\"Called getData\",\"findings\":[\"value=42\"],\"remaining\":\"none\"}"
        );

        AgentEvalResult result = evaluator.evaluate(scenario, sim);

        assertThat(result.passed()).isTrue();
        assertThat(result.score().answerCorrectness()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void shouldReportAllHookEventsInResult() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getWeakTags", "Return weak tags.",
                        input -> Map.of("tags", List.of("dp")));

        // Use maxTurns=8 so plan stage triggers ON_PLAN_VALIDATED
        AgentEvalScenario scenario = new AgentEvalScenario(
                "hooks-test", "Verify all hook events captured",
                "Check weak tags.", registry, AgentEvalRubric.balanced(),
                List.of("getWeakTags"), "dp", 2, true, 8
        );

        AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
        sim.whenTurn(1).respondWith(AgentEvalModelSimulator.plan(List.of(
                step("Check weak tags", "getWeakTags", "Need weak areas", 0)
        )));
        sim.whenTurn(2).respondWith(AgentEvalModelSimulator.toolUse("getWeakTags", Map.of()));
        sim.whenTurn(3).respondWith(AgentEvalModelSimulator.finalAnswer("Your weak area is dp."));

        AgentEvalResult result = evaluator.evaluate(scenario, sim);

        assertThat(result.capturedEvents())
                .extracting(AgentHookEvent::type)
                .contains(
                        AgentHookEventType.BEFORE_MODEL_TURN,
                        AgentHookEventType.AFTER_MODEL_TURN,
                        AgentHookEventType.BEFORE_TOOL_CALL,
                        AgentHookEventType.AFTER_TOOL_CALL,
                        AgentHookEventType.ON_PLAN_VALIDATED
                );
    }

    @Test
    void shouldIncludeFinalStateMessagesInResult() {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("hello", "Say hello.", input -> "world");

        AgentEvalScenario scenario = new AgentEvalScenario(
                "messages-test", "Verify messages are captured",
                "Say hello.", registry, AgentEvalRubric.balanced(),
                List.of("hello"), "world", 2, false, 5
        );

        AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
        sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("hello", Map.of()));
        sim.whenTurn(2).respondWith(AgentEvalModelSimulator.finalAnswer("world"));

        AgentEvalResult result = evaluator.evaluate(scenario, sim);

        assertThat(result.finalState()).isNotNull();
        assertThat(result.finalState().finished()).isTrue();
        assertThat(result.finalState().finalAnswer()).isEqualTo("world");
        assertThat(result.actualTurns()).isEqualTo(2);
    }

    // ---- helpers ----

    private static Map<String, Object> step(String action, String toolName, String rationale, int order) {
        return Map.of("action", action, "toolName", toolName, "rationale", rationale, "order", order);
    }
}
