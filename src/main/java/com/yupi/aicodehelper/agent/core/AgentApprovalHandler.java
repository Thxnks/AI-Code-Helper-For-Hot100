package com.yupi.aicodehelper.agent.core;

@FunctionalInterface
public interface AgentApprovalHandler {
    boolean askForApproval(RegisteredAgentTool tool, ToolUseBlock toolUse);
}
