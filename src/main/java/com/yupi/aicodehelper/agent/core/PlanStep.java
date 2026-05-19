package com.yupi.aicodehelper.agent.core;

import java.util.List;

public record PlanStep(String action, String toolName, String rationale, int order) {

    public boolean isToolStep() {
        return toolName != null && !toolName.isBlank();
    }

    public boolean isReasoningStep() {
        return toolName == null || toolName.isBlank();
    }
}
