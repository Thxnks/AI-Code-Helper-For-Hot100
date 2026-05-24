package com.yupi.aicodehelper.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.aicodehelper.agent.core.AgentHookEvent;
import com.yupi.aicodehelper.agent.core.AgentHookEventType;
import com.yupi.aicodehelper.agent.core.AgentHookManager;
import com.yupi.aicodehelper.agent.core.AgentLoopObserver;
import com.yupi.aicodehelper.agent.core.AgentLoopService;
import com.yupi.aicodehelper.agent.core.AgentLoopState;
import com.yupi.aicodehelper.agent.core.AgentPermissionContext;
import com.yupi.aicodehelper.agent.core.AgentPermissionGate;
import com.yupi.aicodehelper.agent.core.AgentTurnClient;
import com.yupi.aicodehelper.agent.core.PlanStep;
import com.yupi.aicodehelper.agent.core.SkillCatalogService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class AgentEvaluator {

    private static final double DEFAULT_PASS_THRESHOLD = 0.70;

    private final ObjectMapper objectMapper;
    private final AgentLoopService loopService;
    private final AgentTurnClient liveTurnClient;

    public AgentEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.loopService = null;
        this.liveTurnClient = null;
    }

    public AgentEvaluator(AgentLoopService loopService, AgentTurnClient liveTurnClient) {
        this.objectMapper = new ObjectMapper();
        this.loopService = loopService;
        this.liveTurnClient = liveTurnClient;
    }

    public AgentEvalResult evaluate(AgentEvalScenario scenario, AgentEvalModelSimulator simulator) {
        List<AgentHookEvent> events = new ArrayList<>();
        AgentHookManager hookManager = new AgentHookManager(List.of(events::add));

        AgentLoopService ls = new AgentLoopService(
                objectMapper,
                SkillCatalogService.of(Map.of()),
                new AgentPermissionGate(),
                hookManager
        );

        AgentLoopState state;
        try {
            state = ls.run(
                    scenario.userGoal(),
                    scenario.toolRegistry(),
                    simulator,
                    AgentLoopObserver.NOOP,
                    AgentPermissionContext.readOnly(),
                    scenario.maxTurns()
            );
        } catch (Exception e) {
            return failResult(scenario, events, simulator.executedTurns(), e);
        }

        return buildResult(scenario, events, state, simulator.executedTurns());
    }

    public AgentEvalResult evaluateLive(AgentEvalScenario scenario) {
        if (loopService == null || liveTurnClient == null) {
            throw new IllegalStateException(
                    "Live eval requires AgentLoopService and AgentTurnClient. "
                    + "Use the (AgentLoopService, AgentTurnClient) constructor.");
        }

        List<AgentHookEvent> events = new ArrayList<>();
        AgentHookManager hookManager = new AgentHookManager(List.of(events::add));

        AgentLoopService ls = new AgentLoopService(
                objectMapper,
                SkillCatalogService.of(Map.of()),
                new AgentPermissionGate(),
                hookManager
        );

        AgentLoopState state;
        long startedAt = System.currentTimeMillis();
        try {
            state = ls.run(
                    scenario.userGoal(),
                    scenario.toolRegistry(),
                    liveTurnClient,
                    AgentLoopObserver.NOOP,
                    AgentPermissionContext.readOnly(),
                    scenario.maxTurns()
            );
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startedAt;
            System.out.println("  [live eval error, elapsed=" + elapsedMs + "ms] " + e.getMessage());
            return failResult(scenario, events, 0, e);
        }

        int turns = extractModelTurns(events);
        long elapsedMs = System.currentTimeMillis() - startedAt;
        System.out.println("  [live eval done, turns=" + turns + ", elapsed=" + elapsedMs + "ms]");
        return buildResult(scenario, events, state, turns);
    }

    public AgentEvalReport evaluateAll(
            List<AgentEvalScenario> scenarios,
            Function<AgentEvalScenario, AgentEvalModelSimulator> simulatorFactory) {
        List<AgentEvalResult> results = scenarios.stream()
                .map(s -> evaluate(s, simulatorFactory.apply(s)))
                .toList();
        return AgentEvalReport.from(results);
    }

    // ---- Shared result builder ----

    private AgentEvalResult buildResult(AgentEvalScenario scenario,
                                         List<AgentHookEvent> events,
                                         AgentLoopState state,
                                         int turns) {
        List<String> actualToolCalls = extractToolCalls(events);
        List<String> actualRecoveries = extractRecoveries(events);

        double answerCorrectness = scoreAnswerCorrectness(scenario, state);
        double toolSelectionAccuracy = scoreToolSelectionAccuracy(scenario, actualToolCalls);
        double turnEfficiency = scoreTurnEfficiency(scenario, turns);
        double robustness = scoreRobustness(actualRecoveries);
        double planAdherence = scorePlanAdherence(state, actualToolCalls);

        AgentEvalScore score = AgentEvalScore.fromDimensions(
                scenario.rubric(),
                answerCorrectness, toolSelectionAccuracy, turnEfficiency,
                robustness, planAdherence
        );

        boolean hasPlan = state.plan() != null && !state.plan().isEmpty();
        List<String> planStepsUsed = hasPlan
                ? state.plan().stream()
                    .filter(PlanStep::isToolStep)
                    .map(PlanStep::toolName)
                    .filter(actualToolCalls::contains)
                    .toList()
                : List.of();

        double threshold = scenario.rubric().answerCorrectnessWeight() > 0.5
                ? scenario.rubric().answerCorrectnessWeight() * 0.8
                : DEFAULT_PASS_THRESHOLD;
        boolean passed = score.passed(threshold);

        return new AgentEvalResult(
                scenario, score, events, actualToolCalls, actualRecoveries,
                turns, hasPlan, planStepsUsed, state, passed
        );
    }

    // ---- Scoring methods ----

    private double scoreAnswerCorrectness(AgentEvalScenario scenario, AgentLoopState state) {
        if (scenario.expectedAnswer() == null || scenario.expectedAnswer().isBlank()) {
            return 1.0;
        }
        String actual = state.finalAnswer();
        if (actual == null) {
            return 0.0;
        }
        String expected = scenario.expectedAnswer();
        if (actual.trim().equalsIgnoreCase(expected.trim())) {
            return 1.0;
        }
        if (actual.toLowerCase().contains(expected.toLowerCase())) {
            return 0.8;
        }
        String[] expectedWords = expected.toLowerCase().split("\\s+");
        long matched = 0;
        for (String word : expectedWords) {
            if (word.length() >= 3 && actual.toLowerCase().contains(word)) {
                matched++;
            }
        }
        if (matched == 0) {
            return 0.0;
        }
        return Math.min(0.7, (double) matched / expectedWords.length * 0.7);
    }

    private double scoreToolSelectionAccuracy(AgentEvalScenario scenario, List<String> actualToolCalls) {
        if (scenario.expectedToolSequence() == null || scenario.expectedToolSequence().isEmpty()) {
            return 1.0;
        }
        if (actualToolCalls.isEmpty()) {
            return 0.0;
        }
        List<String> expected = scenario.expectedToolSequence();
        int lcs = longestCommonSubsequenceLength(expected, actualToolCalls);
        int maxLen = Math.max(expected.size(), actualToolCalls.size());
        return (double) lcs / maxLen;
    }

    private double scoreTurnEfficiency(AgentEvalScenario scenario, int actualTurns) {
        if (actualTurns <= 0) {
            return 0.0;
        }
        if (actualTurns <= scenario.minExpectedTurns()) {
            return 1.0;
        }
        return Math.min(1.0, (double) scenario.minExpectedTurns() / actualTurns);
    }

    private double scoreRobustness(List<String> recoveryReasons) {
        if (recoveryReasons.isEmpty()) {
            return 1.0;
        }
        double penalty = 0.0;
        for (String reason : recoveryReasons) {
            if (reason.contains("MAX_TURNS")) {
                penalty += 0.5;
            } else {
                penalty += 0.2;
            }
        }
        return Math.max(0.0, 1.0 - penalty);
    }

    private double scorePlanAdherence(AgentLoopState state, List<String> actualToolCalls) {
        List<PlanStep> plan = state.plan();
        if (plan == null || plan.isEmpty()) {
            return 0.0;
        }
        List<String> plannedTools = plan.stream()
                .filter(PlanStep::isToolStep)
                .map(PlanStep::toolName)
                .toList();
        if (plannedTools.isEmpty()) {
            return 1.0;
        }
        long executed = plannedTools.stream()
                .filter(actualToolCalls::contains)
                .count();
        return (double) executed / plannedTools.size();
    }

    // ---- Event extraction ----

    private List<String> extractToolCalls(List<AgentHookEvent> events) {
        return events.stream()
                .filter(e -> e.type() == AgentHookEventType.BEFORE_TOOL_CALL)
                .map(AgentHookEvent::toolName)
                .toList();
    }

    private List<String> extractRecoveries(List<AgentHookEvent> events) {
        return events.stream()
                .filter(e -> e.type() == AgentHookEventType.ON_RECOVERY)
                .map(e -> String.valueOf(e.payload().getOrDefault("type", "UNKNOWN")))
                .toList();
    }

    private int extractModelTurns(List<AgentHookEvent> events) {
        return (int) events.stream()
                .filter(e -> e.type() == AgentHookEventType.BEFORE_MODEL_TURN)
                .count();
    }

    // ---- LCS ----

    private int longestCommonSubsequenceLength(List<String> a, List<String> b) {
        int[][] dp = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp[a.size()][b.size()];
    }

    // ---- Failure result ----

    private AgentEvalResult failResult(AgentEvalScenario scenario,
                                        List<AgentHookEvent> events,
                                        int actualTurns,
                                        Exception error) {
        AgentEvalScore score = AgentEvalScore.fromDimensions(
                scenario.rubric(), 0, 0, 0, 0, 0);
        return new AgentEvalResult(
                scenario, score, events, extractToolCalls(events),
                extractRecoveries(events), actualTurns, false, List.of(),
                new AgentLoopState(scenario.userGoal()), false
        );
    }
}
