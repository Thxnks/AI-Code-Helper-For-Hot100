package com.yupi.aicodehelper.agent.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.aicodehelper.agent.core.AgentTurnClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class AgentEvalModelSimulator implements AgentTurnClient {

    private final List<TurnRule> rules = new ArrayList<>();
    private final ObjectMapper objectMapper;
    private int currentTurn = 0;
    private boolean strictMode = true;

    public AgentEvalModelSimulator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TurnBuilder whenTurn(int turn) {
        return new TurnBuilder(turn);
    }

    public AgentEvalModelSimulator fallback(String response) {
        rules.add(new TurnRule(-1, null, response));
        return this;
    }

    public AgentEvalModelSimulator strictMode(boolean strict) {
        this.strictMode = strict;
        return this;
    }

    public AgentEvalModelSimulator whenPromptMatches(Predicate<String> predicate, String response) {
        rules.add(new TurnRule(-2, predicate, response));
        return this;
    }

    @Override
    public String nextTurn(String prompt) {
        currentTurn++;
        for (TurnRule rule : rules) {
            if (rule.turn == -2 && rule.promptPredicate != null && rule.promptPredicate.test(prompt)) {
                return rule.response;
            }
        }
        for (TurnRule rule : rules) {
            if (rule.turn == currentTurn) {
                return rule.response;
            }
        }
        for (TurnRule rule : rules) {
            if (rule.turn == -1) {
                return rule.response;
            }
        }
        if (strictMode) {
            throw new IllegalStateException(
                    "No rule matched turn " + currentTurn + " and no fallback set");
        }
        if (!rules.isEmpty()) {
            return rules.get(rules.size() - 1).response;
        }
        return "{\"type\":\"final_answer\",\"content\":\"No response configured.\"}";
    }

    public int executedTurns() {
        return currentTurn;
    }

    public static String toolUse(String name, Map<String, Object> input) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("type", "tool_use");
        obj.put("id", "toolu_" + name);
        obj.put("name", name);
        obj.put("input", input == null ? Map.of() : input);
        return toJsonOrString(obj);
    }

    public static String finalAnswer(String content) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("type", "final_answer");
        obj.put("content", content);
        return toJsonOrString(obj);
    }

    public static String plan(List<Map<String, Object>> steps) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("type", "plan");
        obj.put("steps", steps);
        return toJsonOrString(obj);
    }

    private static String toJsonOrString(Object obj) {
        try {
            return new ObjectMapper().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return String.valueOf(obj);
        }
    }

    private record TurnRule(int turn, Predicate<String> promptPredicate, String response) {}

    public class TurnBuilder {
        private final int turn;

        TurnBuilder(int turn) {
            this.turn = turn;
        }

        public AgentEvalModelSimulator respondWith(String jsonResponse) {
            rules.add(new TurnRule(turn, null, jsonResponse));
            return AgentEvalModelSimulator.this;
        }
    }
}
