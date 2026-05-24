package com.yupi.aicodehelper.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AgentLoopState {

    private final List<AgentMessage> messages = new ArrayList<>();
    private final List<TodoItem> todos = new ArrayList<>();
    private final AgentCitationRegistry citationRegistry = new AgentCitationRegistry();
    private final String runId;
    private String resumeFromRunId;
    private AgentCompactSummary compactSummary;
    private int turnCount;
    private String transitionReason;
    private boolean finished;
    private String finalAnswer;
    private List<PlanStep> plan;

    public AgentLoopState(String userMessage) {
        this(userMessage, UUID.randomUUID().toString().replace("-", ""));
    }

    public AgentLoopState(String userMessage, String runId) {
        this.runId = runId;
        this.messages.add(new AgentMessage("user", userMessage));
    }

    public List<AgentMessage> messages() {
        return messages;
    }

    public List<TodoItem> todos() {
        return todos;
    }

    public AgentCitationRegistry citationRegistry() {
        return citationRegistry;
    }

    public void replaceTodos(List<TodoItem> todos) {
        this.todos.clear();
        this.todos.addAll(todos);
    }

    public AgentCompactSummary compactSummary() {
        return compactSummary;
    }

    public void compact(String summary, int turnsCompleted, String tier) {
        this.compactSummary = new AgentCompactSummary(summary, turnsCompleted, tier);
        AgentMessage originalUserMessage = messages.isEmpty() ? null : messages.get(0);
        this.messages.clear();
        if (originalUserMessage != null) {
            this.messages.add(originalUserMessage);
        }
        this.messages.add(new AgentMessage("assistant",
                "[%s — compacted after %d turns]\n%s".formatted(tier, turnsCompleted, summary)));
    }

    public int turnCount() {
        return turnCount;
    }

    public void incrementTurnCount() {
        this.turnCount++;
    }

    public String transitionReason() {
        return transitionReason;
    }

    public void transitionReason(String transitionReason) {
        this.transitionReason = transitionReason;
    }

    public boolean finished() {
        return finished;
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public String runId() {
        return runId;
    }

    public String resumeFromRunId() {
        return resumeFromRunId;
    }

    public void setResumeFromRunId(String resumeFromRunId) {
        this.resumeFromRunId = resumeFromRunId;
    }

    public List<PlanStep> plan() {
        return plan;
    }

    public void setPlan(List<PlanStep> plan) {
        this.plan = plan;
    }

    public void finish(String finalAnswer) {
        this.finished = true;
        this.transitionReason = null;
        this.finalAnswer = finalAnswer;
    }
}
