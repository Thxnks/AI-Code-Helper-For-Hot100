package com.yupi.aicodehelper.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public final class AgentEvalRunner {

    private AgentEvalRunner() {}

    public static void main(String[] args) {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentEvaluator evaluator = new AgentEvaluator(objectMapper);

        List<AgentEvalScenario> scenarios = AgentEvalExampleScenarios.builtInScenarios();

        AgentEvalReport report = evaluator.evaluateAll(scenarios, scenario -> {
            AgentEvalModelSimulator sim = new AgentEvalModelSimulator(objectMapper);
            return switch (scenario.name()) {
                case "perfect-tool-chain" -> {
                    sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("getWeakTags", Map.of()));
                    sim.whenTurn(2).respondWith(AgentEvalModelSimulator.toolUse("recommendNext", Map.of("limit", 1)));
                    sim.whenTurn(3).respondWith(AgentEvalModelSimulator.finalAnswer("I recommend two-sum for DP practice."));
                    yield sim;
                }
                case "recovery-from-unknown-tool" -> {
                    sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("badTool", Map.of()));
                    sim.whenTurn(2).respondWith(AgentEvalModelSimulator.toolUse("getWeakTags", Map.of()));
                    sim.whenTurn(3).respondWith(AgentEvalModelSimulator.finalAnswer("Your weak tag is dp."));
                    yield sim;
                }
                case "max-turns-reached" -> {
                    sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("observe", Map.of()));
                    sim.whenTurn(2).respondWith(AgentEvalModelSimulator.toolUse("observe", Map.of()));
                    sim.fallback(AgentEvalModelSimulator.toolUse("observe", Map.of()));
                    yield sim;
                }
                case "multi-step-with-plan" -> {
                    sim.whenTurn(1).respondWith(AgentEvalModelSimulator.plan(List.of(
                            Map.of("action", "Check weak tags", "toolName", "getWeakTags", "rationale", "Identify weak areas", "order", 0),
                            Map.of("action", "List problems", "toolName", "listProblems", "rationale", "Find DP problems", "order", 1),
                            Map.of("action", "Filter by difficulty", "toolName", "filterByDifficulty", "rationale", "Keep only easy", "order", 2)
                    )));
                    sim.whenTurn(2).respondWith(AgentEvalModelSimulator.toolUse("getWeakTags", Map.of()));
                    sim.whenTurn(3).respondWith(AgentEvalModelSimulator.toolUse("listProblems", Map.of("tag", "dp")));
                    sim.whenTurn(4).respondWith(AgentEvalModelSimulator.toolUse("filterByDifficulty", Map.of("difficulty", "easy")));
                    sim.whenTurn(5).respondWith(AgentEvalModelSimulator.finalAnswer("I recommend climb-stairs."));
                    yield sim;
                }
                case "multi-tool-recommendation" -> {
                    sim.whenTurn(1).respondWith(AgentEvalModelSimulator.toolUse("getTagMastery", Map.of()));
                    sim.whenTurn(2).respondWith(AgentEvalModelSimulator.toolUse("searchProblems", Map.of("tag", "dp")));
                    sim.whenTurn(3).respondWith(AgentEvalModelSimulator.finalAnswer("Practice coin-change next."));
                    yield sim;
                }
                default -> {
                    sim.fallback(AgentEvalModelSimulator.finalAnswer("No simulation configured."));
                    yield sim;
                }
            };
        });

        System.out.println(report.format());
    }
}
