package com.yupi.aicodehelper.agent.core;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class AgentRecoveryPolicy {

    private static final int TRANSIENT_MAX_RETRIES = 2;
    private static final Set<String> TRANSIENT_KEYWORDS = Set.of(
            "timeout", "timed out", "connect", "connection", "refused",
            "unreachable", "network", "socket", "ioexception", "rate",
            "throttle", "503", "502", "429", "too many", "unavailable",
            "reset", "broken pipe", "eof"
    );

    private static final Set<String> SEMANTIC_KEYWORDS = Set.of(
            "not found", "no such", "does not exist", "invalid slug",
            "wrong tool", "use a different", "unexpected strategy",
            "contradiction", "inconsistent", "plan change"
    );

    // ---- Tier 1: Transient ----

    public AgentRecoveryDecision transientError(ToolUseBlock toolUse, Exception error) {
        String message = extractMessage(error);
        return new AgentRecoveryDecision(
                AgentRecoveryType.TRANSIENT,
                true,
                message,
                Map.of(
                        "error", "transient_tool_error",
                        "toolName", toolUse.name(),
                        "message", message,
                        "instruction", "A temporary error occurred. Retry the same tool with the same input."
                ),
                0,
                TRANSIENT_MAX_RETRIES
        );
    }

    // ---- Tier 2: Parameter ----

    public AgentRecoveryDecision invalidModelOutput(String modelOutput, String reason) {
        return new AgentRecoveryDecision(
                AgentRecoveryType.PARAMETER,
                true,
                reason,
                Map.of(
                        "type", "recovery",
                        "error", "invalid_model_output",
                        "reason", reason,
                        "instruction", "Return exactly one JSON object using type=tool_use or type=final_answer.",
                        "previousOutput", modelOutput == null ? "" : modelOutput
                )
        );
    }

    public AgentRecoveryDecision unknownTool(ToolUseBlock toolUse) {
        return new AgentRecoveryDecision(
                AgentRecoveryType.PARAMETER,
                true,
                "Tool is not registered: " + toolUse.name(),
                Map.of(
                        "error", "unknown_tool",
                        "toolName", toolUse.name(),
                        "instruction", "Choose one of the available tools listed in the prompt, or return final_answer."
                )
        );
    }

    public AgentRecoveryDecision parameterError(ToolUseBlock toolUse, Exception error) {
        String message = extractMessage(error);
        return new AgentRecoveryDecision(
                AgentRecoveryType.PARAMETER,
                true,
                message,
                Map.of(
                        "error", "parameter_error",
                        "toolName", toolUse.name(),
                        "message", message,
                        "instruction", "The tool call had invalid parameters. Fix the input and try a corrected tool call."
                )
        );
    }

    // ---- Tier 3: Semantic ----

    public AgentRecoveryDecision semanticError(ToolUseBlock toolUse, Exception error) {
        String message = extractMessage(error);
        return new AgentRecoveryDecision(
                AgentRecoveryType.SEMANTIC,
                true,
                message,
                Map.of(
                        "error", "semantic_error",
                        "toolName", toolUse.name(),
                        "message", message,
                        "instruction", "The chosen approach may be wrong. Reconsider the strategy: do you need a different tool, or should you gather more context first? If the plan is no longer relevant, return a replan request."
                )
        );
    }

    // ---- Smart classification ----

    public AgentRecoveryDecision toolError(ToolUseBlock toolUse, Exception error) {
        String message = extractMessage(error);
        String lower = message.toLowerCase();

        if (matchesAny(lower, TRANSIENT_KEYWORDS)) {
            return transientError(toolUse, error);
        }
        if (matchesAny(lower, SEMANTIC_KEYWORDS)) {
            return semanticError(toolUse, error);
        }
        return parameterError(toolUse, error);
    }

    // ---- Terminal ----

    public AgentRecoveryDecision maxTurns(int maxTurns) {
        return new AgentRecoveryDecision(
                AgentRecoveryType.MAX_TURNS,
                false,
                "Agent stopped after reaching the maximum turn limit.",
                "Agent stopped after reaching the maximum turn limit (" + maxTurns + ")."
        );
    }

    public AgentRecoveryDecision escalateToReplan(String reason) {
        return new AgentRecoveryDecision(
                AgentRecoveryType.SEMANTIC,
                true,
                reason,
                Map.of(
                        "error", "escalated_to_replan",
                        "reason", reason,
                        "instruction", "The current approach has failed repeatedly. Discard the existing plan and create a completely new strategy. Re-examine the original goal and available tools."
                )
        );
    }

    private boolean matchesAny(String lowerMessage, Set<String> keywords) {
        return keywords.stream().anyMatch(lowerMessage::contains);
    }

    private String extractMessage(Exception error) {
        if (error == null) return "(no error message)";
        String msg = error.getMessage();
        if (msg != null && !msg.isBlank()) return msg;
        Throwable cause = error.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return error.getClass().getSimpleName();
    }
}
