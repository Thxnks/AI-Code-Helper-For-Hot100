package com.yupi.aicodehelper.agent.core;

public enum AgentRecoveryType {

    /** Temporary: network timeout, rate limit — auto-retry up to 2 times */
    TRANSIENT,
    /** Parameter-level: bad JSON, unknown tool, wrong args — local ReAct fix */
    PARAMETER,
    /** Semantic: wrong tool choice, strategy failure — global replan */
    SEMANTIC,
    /** Terminal: max turns reached, unrecoverable */
    MAX_TURNS,

    // Legacy (mapped to new types)
    INVALID_MODEL_OUTPUT,
    UNKNOWN_TOOL,
    TOOL_ERROR
}
