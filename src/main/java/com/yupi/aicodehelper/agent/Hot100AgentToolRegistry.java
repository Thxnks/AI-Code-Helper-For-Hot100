package com.yupi.aicodehelper.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.aicodehelper.agent.core.AgentToolRegistry;
import com.yupi.aicodehelper.agent.core.AgentToolPermissionLevel;
import com.yupi.aicodehelper.agent.core.RuntimeTaskService;
import com.yupi.aicodehelper.agent.core.SubAgentResult;
import com.yupi.aicodehelper.agent.core.SubAgentService;
import com.yupi.aicodehelper.ai.AiCodeHelperService;
import com.yupi.aicodehelper.common.ErrorCode;
import com.yupi.aicodehelper.exception.BusinessException;
import com.yupi.aicodehelper.hot100.Hot100ProgressService;
import com.yupi.aicodehelper.hot100.Hot100ProgressUpsertRequest;
import com.yupi.aicodehelper.hot100.Hot100Service;
import com.yupi.aicodehelper.hot100.Hot100WrongAnalysisService;
import com.yupi.aicodehelper.hot100.Hot100WrongAnswerAnalyzeRequest;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class Hot100AgentToolRegistry {

    private static final int DEFAULT_LIMIT = 5;
    private static final int DEFAULT_DAYS = 7;

    private final Hot100Service hot100Service;
    private final Hot100ProgressService hot100ProgressService;
    private final Hot100WrongAnalysisService hot100WrongAnalysisService;
    private final AgentKnowledgeService agentKnowledgeService;
    private final AgentMemoryService agentMemoryService;
    private final SubAgentService subAgentService;
    private final AiCodeHelperService aiCodeHelperService;
    private final ObjectProvider<McpClient> mcpClientProvider;
    private final RuntimeTaskService runtimeTaskService;
    private final ObjectMapper objectMapper;

    public Hot100AgentToolRegistry(Hot100Service hot100Service,
                                   Hot100ProgressService hot100ProgressService,
                                   Hot100WrongAnalysisService hot100WrongAnalysisService,
                                   AgentKnowledgeService agentKnowledgeService,
                                   AgentMemoryService agentMemoryService,
                                   SubAgentService subAgentService,
                                   AiCodeHelperService aiCodeHelperService,
                                   ObjectProvider<McpClient> mcpClientProvider,
                                   RuntimeTaskService runtimeTaskService,
                                   ObjectMapper objectMapper) {
        this.hot100Service = hot100Service;
        this.hot100ProgressService = hot100ProgressService;
        this.hot100WrongAnalysisService = hot100WrongAnalysisService;
        this.agentKnowledgeService = agentKnowledgeService;
        this.agentMemoryService = agentMemoryService;
        this.subAgentService = subAgentService;
        this.aiCodeHelperService = aiCodeHelperService;
        this.mcpClientProvider = mcpClientProvider;
        this.runtimeTaskService = runtimeTaskService;
        this.objectMapper = objectMapper;
    }

    public AgentToolRegistry create(Hot100AgentRunRequest request, Long userId) {
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getUserProgress", "Inspect the user's saved Hot100 progress.", input ->
                        hot100ProgressService.listProgress(userId))
                .register("getWeakTags", "Inspect weak tags inferred from wrong answers.", input ->
                        hot100ProgressService.weakTags(userId))
                .register("getTagMastery", "Inspect practiced, mastered, wrong counts and mastery rate by tag.", input ->
                        hot100ProgressService.tagMastery(userId))
                .register("getProblemDetail", "Inspect one Hot100 problem. Input: problemSlug.", input -> {
                    String slug = stringArg(input, "problemSlug", request.problemSlug());
                    requireText(slug, "problemSlug is required for getProblemDetail");
                    return hot100Service.getProblem(slug);
                })
                .register("updateProgress", "Save progress. Input: problemSlug, status.", AgentToolPermissionLevel.WRITE, input -> {
                    String slug = stringArg(input, "problemSlug", request.problemSlug());
                    String status = stringArg(input, "status", request.status());
                    requireText(slug, "problemSlug is required for updateProgress");
                    requireText(status, "status is required for updateProgress");
                    return hot100ProgressService.upsert(new Hot100ProgressUpsertRequest(
                            slug,
                            status,
                            request.notes(),
                            null,
                            null,
                            null,
                            null
                    ), userId);
                })
                .register("analyzeWrongAnswer", "Analyze wrong code or error evidence and save it to the wrong-book record. Input: problemSlug.", AgentToolPermissionLevel.WRITE, input -> {
                    String slug = stringArg(input, "problemSlug", request.problemSlug());
                    requireText(slug, "problemSlug is required for analyzeWrongAnswer");
                    return hot100WrongAnalysisService.analyzeWrongAnswer(new Hot100WrongAnswerAnalyzeRequest(
                            slug,
                            request.userCode(),
                            request.errorDescription(),
                            request.notes()
                    ), userId);
                })
                .register("getWrongBook", "Inspect wrong-book records and saved analysis.", input ->
                        hot100ProgressService.wrongBookAnalysis(userId))
                .register("recommendNext", "Recommend next problems. Optional input: limit.", input ->
                        hot100ProgressService.recommendNext(intArg(input, "limit", limit(request)), userId))
                .register("aiRecommendations", "Generate coach-style recommendation summary. Optional input: limit.", input ->
                        hot100ProgressService.aiRecommendations(intArg(input, "limit", limit(request)), userId))
                .register("generateStudyPlan", "Generate a 7 or 14 day study plan. Optional input: days.", input ->
                        hot100ProgressService.buildStudyPlan(normalizeDays(intArg(input, "days", days(request))), userId))
                .register("searchProblems", "Search Hot100 problems. Optional input: keyword, tag, difficulty.", input ->
                        hot100Service.listProblems(
                                stringArg(input, "keyword", null),
                                stringArg(input, "tag", null),
                                stringArg(input, "difficulty", null)
                        ))
                .register("retrieveKnowledge", "Retrieve supporting knowledge from Hot100 notes and study docs. Optional input: query, limit.", input ->
                        agentKnowledgeService.retrieve(
                                stringArg(input, "query", request.goal()),
                                intArg(input, "limit", 5)
                        ))
                .register("memory_recall", "Recall long-term user memory. Optional input: query, limit.", input ->
                        agentMemoryService.recall(userId, stringArg(input, "query", request.goal()), intArg(input, "limit", 5)))
                .register("memory_profile", "Summarize long-term memory for the current user. Input: {}.", input ->
                        agentMemoryService.profile(userId))
                .register("memory_save", "Save one long-term memory. Input: type, subject, content, optional scope/importance/source.",
                        AgentToolPermissionLevel.WRITE, input -> agentMemoryService.remember(
                                userId,
                                parseMemoryType(stringArg(input, "type", "NOTE")),
                                stringArg(input, "scope", "hot100"),
                                stringArg(input, "subject", request.goal()),
                                stringArg(input, "content", request.goal()),
                                intArg(input, "importance", 5),
                                stringArg(input, "source", "agent")
                        ))
                .register("analyzeProblemWithSubAgent", "Ask a focused sub-agent to analyze one Hot100 problem. Input: problemSlug.", input ->
                        runProblemSubAgent(input, request))
                .register("reviewWrongAnswerWithSubAgent", "Ask a focused sub-agent to review wrong-answer evidence without writing progress. Input: problemSlug.", input ->
                        runWrongAnswerSubAgent(input, request));
        registerMcpTools(registry);
        registerBackgroundTools(registry, request);
        return registry;
    }

    private SubAgentResult runProblemSubAgent(Map<String, Object> input, Hot100AgentRunRequest request) {
        String slug = stringArg(input, "problemSlug", request.problemSlug());
        requireText(slug, "problemSlug is required for analyzeProblemWithSubAgent");
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getProblemDetail", "Inspect one Hot100 problem. Input: problemSlug.", args ->
                        hot100Service.getProblem(stringArg(args, "problemSlug", slug)))
                .register("retrieveKnowledge", "Retrieve supporting knowledge. Optional input: query, limit.", args ->
                        agentKnowledgeService.retrieve(stringArg(args, "query", request.goal()), intArg(args, "limit", 3)));
        return subAgentService.run(
                "Analyze problem slug=%s for the parent Hot100 agent. Focus on pattern, core idea, pitfalls, and interview notes."
                        .formatted(slug),
                registry,
                aiCodeHelperService::runHot100AgentLoopTurn,
                4
        );
    }

    private SubAgentResult runWrongAnswerSubAgent(Map<String, Object> input, Hot100AgentRunRequest request) {
        String slug = stringArg(input, "problemSlug", request.problemSlug());
        requireText(slug, "problemSlug is required for reviewWrongAnswerWithSubAgent");
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getProblemDetail", "Inspect one Hot100 problem. Input: problemSlug.", args ->
                        hot100Service.getProblem(stringArg(args, "problemSlug", slug)))
                .register("retrieveKnowledge", "Retrieve supporting knowledge. Optional input: query, limit.", args ->
                        agentKnowledgeService.retrieve(stringArg(args, "query", request.goal()), intArg(args, "limit", 3)));
        return subAgentService.run(
                """
                Review wrong-answer evidence for problem slug=%s.
                User code provided: %s
                Error description provided: %s
                Notes: %s
                Return only a concise diagnostic summary for the parent agent. Do not write progress.
                """.formatted(slug, hasText(request.userCode()), hasText(request.errorDescription()), nullToPlaceholder(request.notes())).trim(),
                registry,
                aiCodeHelperService::runHot100AgentLoopTurn,
                4
        );
    }

    private void registerBackgroundTools(AgentToolRegistry registry, Hot100AgentRunRequest request) {
        registry
                .register("background_run",
                        "Launch a background analysis task. Input: {\"description\":\"...\"}. Returns task_id immediately.",
                        AgentToolPermissionLevel.WRITE, input -> {
                            String description = stringArg(input, "description", request.goal());
                            requireText(description, "description is required for background_run");
                            var slot = runtimeTaskService.runBackgroundTask(description, () -> {
                                AgentToolRegistry bgRegistry = new AgentToolRegistry()
                                        .register("retrieveKnowledge",
                                                "Retrieve supporting knowledge. Optional input: query, limit.",
                                                args -> agentKnowledgeService.retrieve(
                                                        stringArg(args, "query", description),
                                                        intArg(args, "limit", 3)));
                                SubAgentResult result = subAgentService.run(
                                        description, bgRegistry,
                                        aiCodeHelperService::runHot100AgentLoopTurn, 3);
                                return result.summary();
                            });
                            return Map.of("task_id", slot.getRuntimeId(), "status", "running");
                        })
                .register("background_check",
                        "Check a background task status. Input: {\"task_id\":\"...\"}.",
                        input -> {
                            String taskId = stringArg(input, "task_id", "");
                            requireText(taskId, "task_id is required for background_check");
                            return runtimeTaskService.get(taskId)
                                    .map(slot -> Map.of(
                                            "task_id", slot.getRuntimeId(),
                                            "status", slot.getStatus().name().toLowerCase(),
                                            "stage", slot.getStage(),
                                            "progress", slot.getProgress(),
                                            "error", slot.getErrorMessage() != null ? slot.getErrorMessage() : "",
                                            "output", slot.getOutput() != null ? slot.getOutput() : ""
                                    ))
                                    .orElse(Map.of("error", "task not found"));
                        });
    }

    private void registerMcpTools(AgentToolRegistry registry) {
        McpClient mcpClient = mcpClientProvider.getIfAvailable();
        if (mcpClient == null) {
            return;
        }
        try {
            for (var spec : mcpClient.listTools()) {
                String name = spec.name();
                String mcpName = "mcp_" + name;
                String description = buildMcpDescription(spec);
                registry.register(mcpName, description, AgentToolPermissionLevel.EXTERNAL, input -> {
                    try {
                        String args = objectMapper.writeValueAsString(input);
                        ToolExecutionRequest request = ToolExecutionRequest.builder()
                                .name(name)
                                .arguments(args)
                                .build();
                        return mcpClient.executeTool(request);
                    } catch (Exception e) {
                        throw new RuntimeException("MCP tool " + name + " failed: " + e.getMessage(), e);
                    }
                });
            }
        } catch (Exception ignored) {
            // MCP server unavailable — skip external tools
        }
    }

    private String buildMcpDescription(dev.langchain4j.agent.tool.ToolSpecification spec) {
        StringBuilder sb = new StringBuilder();
        sb.append(spec.description() != null ? spec.description() : "MCP external tool");
        var params = spec.parameters();
        if (params != null && params.properties() != null && !params.properties().isEmpty()) {
            sb.append(". Input: {");
            boolean first = true;
            for (var prop : params.properties().entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(prop.getKey());
                if (params.required() != null && params.required().contains(prop.getKey())) {
                    sb.append(" (required)");
                }
            }
            sb.append("}");
        }
        return sb.toString();
    }

    private int limit(Hot100AgentRunRequest request) {
        return request.limit() == null ? DEFAULT_LIMIT : Math.max(1, Math.min(request.limit(), 20));
    }

    private int days(Hot100AgentRunRequest request) {
        return request.days() == null ? DEFAULT_DAYS : normalizeDays(request.days());
    }

    private int normalizeDays(int days) {
        return days <= 7 ? 7 : 14;
    }

    private String stringArg(Map<String, Object> input, String key, String fallback) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private int intArg(Map<String, Object> input, String key, int fallback) {
        Object value = input == null ? null : input.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    private AgentMemoryType parseMemoryType(String value) {
        if (value == null || value.isBlank()) {
            return AgentMemoryType.NOTE;
        }
        try {
            return AgentMemoryType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return AgentMemoryType.NOTE;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToPlaceholder(String value) {
        return value == null || value.isBlank() ? "(not provided)" : value;
    }
}
