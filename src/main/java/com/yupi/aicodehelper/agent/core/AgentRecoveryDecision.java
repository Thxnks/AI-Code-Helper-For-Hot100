package com.yupi.aicodehelper.agent.core;

public record AgentRecoveryDecision(
        AgentRecoveryType type,
        boolean retry,
        String reason,
        Object content,
        int retryCount,
        int maxRetries
) {
    public AgentRecoveryDecision(AgentRecoveryType type, boolean retry, String reason, Object content) {
        this(type, retry, reason, content, 0, 0);
    }

    public boolean canRetry() {
        return retry && retryCount < maxRetries;
    }

    public AgentRecoveryDecision withIncrementedRetry() {
        return new AgentRecoveryDecision(type, retry, reason, content, retryCount + 1, maxRetries);
    }
}
