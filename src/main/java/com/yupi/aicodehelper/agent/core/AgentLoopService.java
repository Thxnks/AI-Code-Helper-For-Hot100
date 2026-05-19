package com.yupi.aicodehelper.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AgentLoopService {

    private static final int DEFAULT_MAX_TURNS = 8;

    // Tier 1: Snip — remove older, lower-scoring messages
    private static final int TIER1_MAX_MESSAGES = 10;
    private static final int TIER1_MAX_CHARS = 8000;

    // Tier 2: Microcompact — compress large tool outputs
    private static final int TIER2_MAX_MESSAGES = 16;
    private static final int TIER2_MAX_CHARS = 12000;

    // Tier 3: Autocompact — model-generated summary
    private static final int TIER3_MAX_CHARS = 15000;

    private static final int TOOL_RESULT_COMPRESS_THRESHOLD = 600;
    private static final int SNIP_KEEP_LAST = 4;

    private final ObjectMapper objectMapper;
    private final SkillCatalogService skillCatalogService;
    private final AgentPermissionGate permissionGate;
    private final AgentHookManager hookManager;
    private final AgentPromptBuilder promptBuilder;
    private final AgentRecoveryPolicy recoveryPolicy;
    private final TaskGraphService taskGraphService;
    private final RuntimeTaskService runtimeTaskService;
    private final AgentPlanValidator planValidator;
    private final ToolIdempotencyGuard idempotencyGuard;

    public AgentLoopService(ObjectMapper objectMapper) {
        this(objectMapper, SkillCatalogService.of(Map.of()), new AgentPermissionGate(), new AgentHookManager(),
                new AgentPromptBuilder(objectMapper), new AgentRecoveryPolicy(),
                new TaskGraphService(new InMemoryTaskBoard()), null, new AgentPlanValidator(), null);
    }

    public AgentLoopService(ObjectMapper objectMapper, SkillCatalogService skillCatalogService) {
        this(objectMapper, skillCatalogService, new AgentPermissionGate(), new AgentHookManager(),
                new AgentPromptBuilder(objectMapper), new AgentRecoveryPolicy(),
                new TaskGraphService(new InMemoryTaskBoard()), null, new AgentPlanValidator(), null);
    }

    public AgentLoopService(ObjectMapper objectMapper,
                            SkillCatalogService skillCatalogService,
                            AgentPermissionGate permissionGate) {
        this(objectMapper, skillCatalogService, permissionGate, new AgentHookManager(),
                new AgentPromptBuilder(objectMapper), new AgentRecoveryPolicy(),
                new TaskGraphService(new InMemoryTaskBoard()), null, new AgentPlanValidator(), null);
    }

    public AgentLoopService(ObjectMapper objectMapper,
                            SkillCatalogService skillCatalogService,
                            AgentPermissionGate permissionGate,
                            AgentHookManager hookManager) {
        this(objectMapper, skillCatalogService, permissionGate, hookManager, new AgentPromptBuilder(objectMapper),
                new AgentRecoveryPolicy(), new TaskGraphService(new InMemoryTaskBoard()), null,
                new AgentPlanValidator(), null);
    }

    @Autowired
    public AgentLoopService(ObjectMapper objectMapper,
                            SkillCatalogService skillCatalogService,
                            AgentPermissionGate permissionGate,
                            AgentHookManager hookManager,
                            AgentPromptBuilder promptBuilder,
                            AgentRecoveryPolicy recoveryPolicy,
                            TaskBoard taskBoard,
                            ObjectProvider<RuntimeTaskService> runtimeTaskServiceProvider,
                            ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider) {
        this(objectMapper, skillCatalogService, permissionGate, hookManager, promptBuilder, recoveryPolicy,
                new TaskGraphService(taskBoard),
                runtimeTaskServiceProvider.getIfAvailable(),
                new AgentPlanValidator(),
                new ToolIdempotencyGuard(stringRedisTemplateProvider.getIfAvailable(), objectMapper));
    }

    public AgentLoopService(ObjectMapper objectMapper,
                            SkillCatalogService skillCatalogService,
                            AgentPermissionGate permissionGate,
                            AgentHookManager hookManager,
                            AgentPromptBuilder promptBuilder,
                            AgentRecoveryPolicy recoveryPolicy,
                            TaskGraphService taskGraphService,
                            RuntimeTaskService runtimeTaskService,
                            AgentPlanValidator planValidator,
                            ToolIdempotencyGuard idempotencyGuard) {
        this.objectMapper = objectMapper;
        this.skillCatalogService = skillCatalogService;
        this.permissionGate = permissionGate;
        this.hookManager = hookManager;
        this.promptBuilder = promptBuilder;
        this.recoveryPolicy = recoveryPolicy;
        this.taskGraphService = taskGraphService;
        this.runtimeTaskService = runtimeTaskService;
        this.planValidator = planValidator;
        this.idempotencyGuard = idempotencyGuard;
    }

    public AgentLoopState run(String userGoal,
                              AgentToolRegistry toolRegistry,
                              AgentTurnClient turnClient,
                              AgentLoopObserver observer) {
        return run(userGoal, toolRegistry, turnClient, observer, DEFAULT_MAX_TURNS);
    }

    public AgentLoopState run(String userGoal,
                              AgentToolRegistry toolRegistry,
                              AgentTurnClient turnClient,
                              AgentLoopObserver observer,
                              AgentPermissionContext permissionContext) {
        return run(userGoal, toolRegistry, turnClient, observer, permissionContext, DEFAULT_MAX_TURNS);
    }

    public AgentLoopState run(String userGoal,
                              AgentToolRegistry toolRegistry,
                              AgentTurnClient turnClient,
                              AgentLoopObserver observer,
                              int maxTurns) {
        return run(userGoal, toolRegistry, turnClient, observer, AgentPermissionContext.readOnly(), maxTurns);
    }

    public AgentLoopState run(String userGoal,
                              AgentToolRegistry toolRegistry,
                              AgentTurnClient turnClient,
                              AgentLoopObserver observer,
                              AgentPermissionContext permissionContext,
                              int maxTurns) {
        AgentLoopState state = new AgentLoopState(userGoal);
        registerTodoTools(toolRegistry, state);
        registerSkillTools(toolRegistry);
        registerTaskTools(toolRegistry);
        AgentLoopObserver safeObserver = observer == null ? AgentLoopObserver.NOOP : observer;

        if (maxTurns >= DEFAULT_MAX_TURNS) {
            runPlanStage(state, toolRegistry, turnClient, safeObserver);
        }

        while (!state.finished() && state.turnCount() < maxTurns) {
            state.incrementTurnCount();
            drainBackgroundNotifications(state);
            compactIfNeeded(state, turnClient);
            String prompt = promptBuilder.build(new AgentPromptContext(state, toolRegistry));
            publishHook(AgentHookEventType.BEFORE_MODEL_TURN, state.turnCount(), null, Map.of(
                    "promptLength", prompt.length()
            ));
            long modelStartedAt = System.currentTimeMillis();
            String modelOutput = turnClient.nextTurn(prompt);
            long modelLatencyMs = System.currentTimeMillis() - modelStartedAt;
            safeObserver.onModelTurn(state.turnCount(), prompt, modelOutput, modelLatencyMs);
            publishHook(AgentHookEventType.AFTER_MODEL_TURN, state.turnCount(), null, Map.of(
                    "promptLength", prompt.length(),
                    "outputLength", modelOutput == null ? 0 : modelOutput.length(),
                    "latencyMs", modelLatencyMs
            ));
            state.messages().add(new AgentMessage("assistant", modelOutput));

            AgentTurnDecision decision = parseDecision(modelOutput);
            if (decision.recoveryDecision() != null) {
                applyRecovery(state, decision.recoveryDecision());
                continue;
            }
            if (decision.finalAnswer() != null) {
                state.finish(decision.finalAnswer());
                break;
            }

            ToolUseBlock toolUse = decision.toolUse();
            if (toolUse == null) {
                state.finish(modelOutput);
                break;
            }

            RegisteredAgentTool tool = toolRegistry.find(toolUse.name()).orElse(null);
            if (tool == null) {
                applyToolRecovery(state, toolUse, recoveryPolicy.unknownTool(toolUse));
                continue;
            }

            long toolStartedAt = System.currentTimeMillis();
            publishHook(AgentHookEventType.BEFORE_TOOL_CALL, state.turnCount(), toolUse.name(), Map.of(
                    "input", toolUse.input(),
                    "permissionLevel", tool.spec().permissionLevel()
            ));
            AgentPermissionDecision permissionDecision = permissionGate.check(tool, permissionContext);
            if (!permissionDecision.allowed()) {
                AgentPermissionDeniedException error = new AgentPermissionDeniedException(
                        "Permission denied for tool " + toolUse.name() + ": " + permissionDecision.reason()
                );
                safeObserver.onToolError(state.turnCount(), toolUse.name(), toolUse.input(), error,
                        System.currentTimeMillis() - toolStartedAt);
                publishHook(AgentHookEventType.ON_PERMISSION_DENIED, state.turnCount(), toolUse.name(), Map.of(
                        "input", toolUse.input(),
                        "permissionLevel", tool.spec().permissionLevel(),
                        "reason", permissionDecision.reason()
                ));
                appendToolResult(state, toolUse, Map.of(
                        "error", "permission_denied",
                        "toolName", toolUse.name(),
                        "permissionLevel", tool.spec().permissionLevel(),
                        "reason", permissionDecision.reason()
                ));
                state.transitionReason("tool_result");
                continue;
            }
            // Idempotency check: skip duplicate tool calls within TTL window
            if (idempotencyGuard != null && idempotencyGuard.isDuplicate(toolUse.name(), toolUse.input())) {
                String cached = idempotencyGuard.getCachedResult(toolUse.name(), toolUse.input());
                if (cached != null) {
                    appendToolResult(state, toolUse, Map.of(
                            "_idempotent", true,
                            "cachedResult", cached
                    ));
                    state.transitionReason("tool_result");
                    continue;
                }
            }
            try {
                Object output = executeWithRetry(toolUse, tool, state, safeObserver, toolStartedAt);
                if (output == null) continue; // retry/semantic recovery already handled
                long toolLatencyMs = System.currentTimeMillis() - toolStartedAt;
                safeObserver.onToolResult(state.turnCount(), toolUse.name(), toolUse.input(), output,
                        toolLatencyMs);
                publishHook(AgentHookEventType.AFTER_TOOL_CALL, state.turnCount(), toolUse.name(), Map.of(
                        "input", toolUse.input(),
                        "output", output,
                        "latencyMs", toolLatencyMs
                ));
                appendToolResult(state, toolUse, output);
                if (idempotencyGuard != null) {
                    idempotencyGuard.markExecuted(toolUse.name(), toolUse.input(), output);
                }
            } catch (Exception e) {
                long toolLatencyMs = System.currentTimeMillis() - toolStartedAt;
                safeObserver.onToolError(state.turnCount(), toolUse.name(), toolUse.input(), e,
                        toolLatencyMs);
                publishHook(AgentHookEventType.ON_TOOL_ERROR, state.turnCount(), toolUse.name(), Map.of(
                        "input", toolUse.input(),
                        "error", blankToPlaceholder(e.getMessage()),
                        "latencyMs", toolLatencyMs
                ));
                AgentRecoveryDecision recoveryDecision = recoveryPolicy.toolError(toolUse, e);
                applyTieredRecovery(state, toolUse, recoveryDecision);
            }
            state.transitionReason("tool_result");
        }

        return finalizeState(state, maxTurns);
    }

    private void runPlanStage(AgentLoopState state, AgentToolRegistry toolRegistry,
                              AgentTurnClient turnClient, AgentLoopObserver safeObserver) {
        for (int attempt = 1; attempt <= planValidator.maxAttempts(); attempt++) {
            String planPrompt = buildPlanPrompt(state, toolRegistry);
            String planOutput = turnClient.nextTurn(planPrompt);
            List<PlanStep> steps = parsePlan(planOutput);

            AgentPlanValidator.PlanValidationResult result = planValidator.validate(steps, toolRegistry);
            if (result.valid()) {
                state.setPlan(result.steps());
                state.messages().add(new AgentMessage("assistant",
                        "[Plan validated — %d steps]\n%s".formatted(result.steps().size(),
                                planOutput)));
                publishHook(AgentHookEventType.ON_PLAN_VALIDATED, 0, null, Map.of(
                        "steps", result.steps().size(),
                        "attempt", attempt
                ));
                return;
            }

            if (attempt < planValidator.maxAttempts()) {
                state.messages().add(new AgentMessage("user", result.toReplanPrompt()));
            } else {
                state.setPlan(result.steps().isEmpty() ? List.of() : result.steps());
                state.messages().add(new AgentMessage("assistant",
                        "[Plan validation failed after %d attempts — proceeding with ReAct loop]\nIssues: %s"
                                .formatted(attempt, String.join("; ", result.issues()))));
                publishHook(AgentHookEventType.ON_PLAN_FAILED, 0, null, Map.of(
                        "attempts", attempt,
                        "issues", result.issues().size()
                ));
            }
        }
    }

    private String buildPlanPrompt(AgentLoopState state, AgentToolRegistry toolRegistry) {
        return """
                You are a planning agent. Before executing any work, produce a step-by-step execution plan.

                User goal:
                %s

                Available tools:
                %s

                Output format (JSON only):
                {"type":"plan","steps":[
                  {"action":"what to do","toolName":"tool_or_empty","rationale":"why","order":0},
                  {"action":"next step","toolName":"another_tool","rationale":"why","order":1}
                ]}

                Rules:
                - Each step must have a clear action description.
                - If a step needs data, use a tool (set toolName to the exact tool name from Available tools).
                - If a step is reasoning/analysis only, leave toolName empty.
                - order must be 0,1,2,... in sequence.
                - At least one step must use a tool.
                - Maximum 8 steps.
                - Think step-by-step: first gather data, then analyze, then answer.
                """.formatted(
                state.messages().isEmpty() ? "unknown" : state.messages().get(0).content(),
                toolRegistry.describeAvailableTools()
        );
    }

    private List<PlanStep> parsePlan(String modelOutput) {
        try {
            String json = extractJsonObject(modelOutput);
            JsonNode root = objectMapper.readTree(json);
            if (!"plan".equals(root.path("type").asText(""))) {
                return List.of();
            }
            JsonNode stepsNode = root.path("steps");
            if (!stepsNode.isArray()) {
                return List.of();
            }
            List<PlanStep> steps = new ArrayList<>();
            for (JsonNode step : stepsNode) {
                steps.add(new PlanStep(
                        step.path("action").asText(""),
                        step.path("toolName").asText(""),
                        step.path("rationale").asText(""),
                        step.path("order").asInt(steps.size())
                ));
            }
            return steps;
        } catch (Exception e) {
            return List.of();
        }
    }

    private AgentLoopState finalizeState(AgentLoopState state, int maxTurns) {
        if (!state.finished()) {
            AgentRecoveryDecision recoveryDecision = recoveryPolicy.maxTurns(maxTurns);
            publishRecoveryHook(state.turnCount(), null, recoveryDecision);
            state.finish(String.valueOf(recoveryDecision.content()));
        }
        return state;
    }

    private void registerTodoTools(AgentToolRegistry toolRegistry, AgentLoopState state) {
        toolRegistry
                .register("todo_read", "Read the current in-session todo list. Input: {}.", input ->
                        new TodoWriteResult(List.copyOf(state.todos())))
                .register("todo_write", "Replace the in-session todo list. Input: {\"todos\":[{\"content\":\"...\",\"status\":\"PENDING|IN_PROGRESS|COMPLETED\"}]}.", input -> {
                    List<TodoItem> todos = parseTodos(input);
                    state.replaceTodos(todos);
                    return new TodoWriteResult(List.copyOf(state.todos()));
                });
    }

    private void registerSkillTools(AgentToolRegistry toolRegistry) {
        toolRegistry
                .register("list_skills", "List locally available skills. Input: {}.", input ->
                        skillCatalogService.listSkills())
                .register("load_skill", "Load one local skill by name. Input: {\"name\":\"skill-name\"}.", input ->
                        skillCatalogService.loadSkill(stringArg(input, "name")));
    }

    private void registerTaskTools(AgentToolRegistry toolRegistry) {
        toolRegistry
                .register("task_create", "Create one persistent task. Input: subject, optional description/owner/blockedBy[].", input -> {
                    String subject = stringArg(input, "subject");
                    if (subject.isBlank()) {
                        throw new IllegalArgumentException("subject is required for task_create");
                    }
                    TaskRecord task = taskGraphService.create(
                            subject,
                            stringArg(input, "description"),
                            stringArg(input, "owner"),
                            longListArg(input, "blockedBy")
                    );
                    return taskToMap(task);
                })
                .register("task_update", "Update a persistent task. Input: id plus optional status/subject/description/owner/addBlocks/removeBlocks/blockedBy.", input -> {
                    long id = longArg(input, "id");
                    TaskRecord task = taskGraphService.update(
                            id,
                            nullableStringArg(input, "subject"),
                            nullableStringArg(input, "description"),
                            nullableStringArg(input, "owner"),
                            parseTaskStatus(nullableStringArg(input, "status")),
                            nullableLongListArg(input, "addBlocks"),
                            nullableLongListArg(input, "removeBlocks"),
                            nullableLongListArg(input, "blockedBy")
                    );
                    return taskToMap(task);
                })
                .register("task_get", "Get one persistent task by id. Input: id.", input -> {
                    long id = longArg(input, "id");
                    return taskToMap(taskGraphService.get(id));
                })
                .register("task_list", "List persistent tasks. Input: optional includeDeleted.", input ->
                        taskGraphService.list(booleanArg(input, "includeDeleted", false)));
    }

    private void drainBackgroundNotifications(AgentLoopState state) {
        if (runtimeTaskService == null) {
            return;
        }
        List<BackgroundNotification> notifications = runtimeTaskService.drainNotifications();
        if (notifications.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("[Background notifications]\n");
        for (BackgroundNotification n : notifications) {
            sb.append("- [").append(n.taskId()).append("] ")
                    .append(n.status()).append(": ").append(n.preview()).append("\n");
        }
        state.messages().add(new AgentMessage("user", sb.toString().trim()));
    }

    private void compactIfNeeded(AgentLoopState state, AgentTurnClient turnClient) {
        String rendered = promptBuilder.renderMessages(state);
        int msgCount = state.messages().size();

        // No compaction needed
        if (msgCount <= TIER1_MAX_MESSAGES && rendered.length() <= TIER1_MAX_CHARS) {
            return;
        }

        // ---- Tier 1: Snip — score and remove low-value old messages ----
        boolean needsMore = applySnip(state);
        publishHook(AgentHookEventType.ON_COMPACT, state.turnCount(), null, Map.of(
                "tier", "TIER1_SNIP",
                "messageCount", state.messages().size()
        ));
        if (!needsMore) return;

        // ---- Tier 2: Microcompact — trim large tool outputs ----
        needsMore = applyMicrocompact(state);
        publishHook(AgentHookEventType.ON_COMPACT, state.turnCount(), null, Map.of(
                "tier", "TIER2_MICROCOMPACT",
                "messageCount", state.messages().size()
        ));
        if (!needsMore) return;

        // ---- Tier 3: Autocompact — model-generated structured summary ----
        applyAutocompact(state, turnClient);
        publishHook(AgentHookEventType.ON_COMPACT, state.turnCount(), null, Map.of(
                "tier", "TIER3_AUTOCOMPACT",
                "messageCount", state.messages().size()
        ));
    }

    // ---- Tier 1: Snip — score and remove low-value old messages ----
    private boolean applySnip(AgentLoopState state) {
        List<AgentMessage> messages = state.messages();
        int total = messages.size();
        if (total <= TIER1_MAX_MESSAGES) {
            return needsMoreCompaction(state);
        }

        // Score each message, keep highest-scoring ones
        java.util.List<ScoredMessage> scored = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            scored.add(new ScoredMessage(i, snipScore(i, total, messages.get(i))));
        }
        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        int keepCount = Math.max(3, TIER1_MAX_MESSAGES - SNIP_KEEP_LAST);
        boolean[] keep = new boolean[total];
        // Always keep first message (original goal) and last N messages
        for (var sm : scored) {
            if (sm.index == 0 || sm.index >= total - SNIP_KEEP_LAST) {
                keep[sm.index] = true;
            }
        }
        int kept = 0;
        for (var sm : scored) {
            if (kept >= keepCount) break;
            if (!keep[sm.index]) {
                keep[sm.index] = true;
                kept++;
            }
        }

        // Remove low-scoring messages (iterate backwards to preserve indices)
        for (int i = total - 1; i >= 0; i--) {
            if (!keep[i]) {
                messages.remove(i);
            }
        }

        return needsMoreCompaction(state);
    }

    private int snipScore(int index, int total, AgentMessage msg) {
        if (index == 0) return 100;
        if (index >= total - SNIP_KEEP_LAST) return 100;
        int base = "assistant".equals(msg.role()) ? 8 : 6;
        int agePenalty = (index * 5) / total;
        return Math.max(1, base - agePenalty);
    }

    // ---- Tier 2: Microcompact — trim large tool outputs in-place ----
    private boolean applyMicrocompact(AgentLoopState state) {
        List<AgentMessage> messages = state.messages();
        if (!needsMoreCompaction(state)) {
            return false;
        }

        int compressed = 0;
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage msg = messages.get(i);
            if ("user".equals(msg.role()) && msg.content().length() > TOOL_RESULT_COMPRESS_THRESHOLD) {
                String trimmed = compressToolOutput(msg.content());
                if (trimmed.length() < msg.content().length()) {
                    messages.set(i, new AgentMessage(msg.role(), trimmed));
                    compressed++;
                }
            }
        }

        return compressed > 0 ? needsMoreCompaction(state) : true;
    }

    private String compressToolOutput(String content) {
        try {
            var node = objectMapper.readTree(content);
            if (node.isObject()) {
                var trimmed = objectMapper.createObjectNode();
                int added = 0;
                var fields = node.fields();
                while (fields.hasNext() && added < 8) {
                    var field = fields.next();
                    String value = field.getValue().isArray()
                            ? "[... %d items]".formatted(field.getValue().size())
                            : truncate(field.getValue().toPrettyString(), 200);
                    trimmed.put(field.getKey(), value);
                    added++;
                }
                if (node.size() > added) {
                    trimmed.put("_compacted", "%d fields omitted".formatted(node.size() - added));
                }
                return objectMapper.writeValueAsString(trimmed);
            }
            if (node.isArray() && node.size() > 5) {
                var trimmed = objectMapper.createArrayNode();
                for (int j = 0; j < 5; j++) {
                    trimmed.add(node.get(j));
                }
                var wrapper = objectMapper.createObjectNode();
                wrapper.put("_compacted", "Array trimmed from %d to %d".formatted(node.size(), 5));
                wrapper.set("_items", trimmed);
                return objectMapper.writeValueAsString(wrapper);
            }
        } catch (Exception ignored) {
        }
        return truncate(content, 600);
    }

    // ---- Tier 3: Autocompact — model-generated structured summary ----
    private void applyAutocompact(AgentLoopState state, AgentTurnClient turnClient) {
        if (!needsMoreCompaction(state)) {
            return;
        }

        List<AgentMessage> messages = state.messages();
        StringBuilder context = new StringBuilder();
        if (!messages.isEmpty()) {
            context.append("Goal: ").append(truncate(messages.get(0).content(), 2000)).append("\n\n");
        }
        int start = Math.max(1, messages.size() - 10);
        for (int i = start; i < messages.size(); i++) {
            var msg = messages.get(i);
            context.append(msg.role()).append(": ")
                    .append(truncate(msg.content(), 800))
                    .append("\n");
        }

        String compactPrompt = """
                Summarize this agent conversation. Output JSON only:
                {"goal":"restate goal","done":"achievements","findings":["key1","key2"],"remaining":"pending work"}

                Context:
                %s""".formatted(context.toString().trim());

        try {
            String modelOutput = turnClient.nextTurn(compactPrompt);
            String json = extractJsonObject(modelOutput);
            var node = objectMapper.readTree(json);
            String goal = node.has("goal") ? node.get("goal").asText("") : "";
            String done = node.has("done") ? node.get("done").asText("") : "";
            String remaining = node.has("remaining") ? node.get("remaining").asText("") : "";
            var findings = node.has("findings") ? node.get("findings") : objectMapper.createArrayNode();

            StringBuilder summary = new StringBuilder();
            if (!goal.isBlank()) summary.append("Goal: ").append(goal).append("\n");
            if (!done.isBlank()) summary.append("Done: ").append(done).append("\n");
            if (findings.size() > 0) {
                summary.append("Findings:\n");
                for (var f : findings) {
                    summary.append("  - ").append(f.asText()).append("\n");
                }
            }
            if (!remaining.isBlank()) summary.append("Remaining: ").append(remaining);
            summary.append("\nTodos: ").append(promptBuilder.renderTodos(state));

            state.compact(summary.toString().trim(), state.turnCount(), "TIER3_AUTOCOMPACT");
        } catch (Exception ignored) {
            // Model compaction failed — hard truncation fallback
            List<AgentMessage> trimmed = new ArrayList<>();
            if (!messages.isEmpty()) {
                trimmed.add(messages.get(0));
            }
            trimmed.add(new AgentMessage("assistant",
                    "COMPACT (model unavailable) — Turns: %d, Todos: %s"
                            .formatted(state.turnCount(), promptBuilder.renderTodos(state))));
            for (int i = Math.max(1, messages.size() - SNIP_KEEP_LAST); i < messages.size(); i++) {
                trimmed.add(new AgentMessage(messages.get(i).role(),
                        truncate(messages.get(i).content(), 800)));
            }
            messages.clear();
            messages.addAll(trimmed);
        }
    }

    private boolean needsMoreCompaction(AgentLoopState state) {
        String rendered = promptBuilder.renderMessages(state);
        return state.messages().size() > TIER2_MAX_MESSAGES || rendered.length() > TIER3_MAX_CHARS;
    }

    private record ScoredMessage(int index, int score) {}

    private AgentTurnDecision parseDecision(String modelOutput) {
        try {
            String json = extractJsonObject(modelOutput);
            JsonNode root = objectMapper.readTree(json);
            String type = root.path("type").asText("");
            if ("final_answer".equals(type)) {
                return AgentTurnDecision.finalAnswer(root.path("content").asText(""));
            }
            if ("tool_use".equals(type)) {
                String id = root.path("id").asText("");
                String name = root.path("name").asText("");
                Map<String, Object> input = root.path("input").isObject()
                        ? objectMapper.convertValue(root.path("input"), Map.class)
                        : new LinkedHashMap<>();
                if (id.isBlank()) {
                    id = "toolu_" + UUID.randomUUID().toString().replace("-", "");
                }
                return AgentTurnDecision.toolUse(new ToolUseBlock(id, name, input));
            }
            return AgentTurnDecision.recovery(recoveryPolicy.invalidModelOutput(modelOutput,
                    "Unknown response type: " + type));
        } catch (Exception e) {
            return AgentTurnDecision.recovery(recoveryPolicy.invalidModelOutput(modelOutput,
                    blankToPlaceholder(e.getMessage())));
        }
    }

    private List<TodoItem> parseTodos(Map<String, Object> input) {
        Object rawTodos = input == null ? null : input.get("todos");
        if (!(rawTodos instanceof List<?> rawList)) {
            return List.of();
        }
        List<TodoItem> todos = new ArrayList<>();
        for (Object rawItem : rawList) {
            if (!(rawItem instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Object rawContent = rawMap.get("content");
            if (rawContent == null || String.valueOf(rawContent).isBlank()) {
                continue;
            }
            TodoStatus status = parseTodoStatus(rawMap.get("status"));
            todos.add(new TodoItem(String.valueOf(rawContent).trim(), status));
        }
        return todos;
    }

    private TodoStatus parseTodoStatus(Object value) {
        if (value == null) {
            return TodoStatus.PENDING;
        }
        try {
            return TodoStatus.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TodoStatus.PENDING;
        }
    }

    private String stringArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String nullableStringArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private long longArg(Map<String, Object> input, String key) {
        String value = stringArg(input, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private List<Long> longListArg(Map<String, Object> input, String key) {
        List<Long> value = nullableLongListArg(input, key);
        return value == null ? List.of() : value;
    }

    private List<Long> nullableLongListArg(Map<String, Object> input, String key) {
        Object raw = input == null ? null : input.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        List<Long> result = new ArrayList<>();
        for (Object item : rawList) {
            if (item == null) {
                continue;
            }
            try {
                result.add(Long.parseLong(String.valueOf(item)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " contains non-number item");
            }
        }
        return result;
    }

    private boolean booleanArg(Map<String, Object> input, String key, boolean defaultValue) {
        Object raw = input == null ? null : input.get(key);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private TaskStatus parseTaskStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(rawStatus.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status must be one of PENDING|IN_PROGRESS|COMPLETED|DELETED");
        }
    }

    private Map<String, Object> taskToMap(TaskRecord task) {
        return Map.of(
                "id", task.getId(),
                "subject", Objects.toString(task.getSubject(), ""),
                "description", Objects.toString(task.getDescription(), ""),
                "status", task.getStatus(),
                "blockedBy", List.copyOf(task.getBlockedBy()),
                "blocks", List.copyOf(task.getBlocks()),
                "owner", Objects.toString(task.getOwner(), ""),
                "ready", task.isReady()
        );
    }

    private void appendToolResult(AgentLoopState state, ToolUseBlock toolUse, Object output) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUse.id());
        block.put("name", toolUse.name());
        block.put("content", output);
        state.messages().add(new AgentMessage("user", toJson(block)));
    }

    private void applyRecovery(AgentLoopState state, AgentRecoveryDecision recoveryDecision) {
        publishRecoveryHook(state.turnCount(), null, recoveryDecision);
        state.messages().add(new AgentMessage("user", toJson(recoveryDecision.content())));
        state.transitionReason("recovery");
    }

    private void applyToolRecovery(AgentLoopState state,
                                   ToolUseBlock toolUse,
                                   AgentRecoveryDecision recoveryDecision) {
        publishRecoveryHook(state.turnCount(), toolUse.name(), recoveryDecision);
        appendToolResult(state, toolUse, recoveryDecision.content());
        state.transitionReason("tool_result");
    }

    private Object executeWithRetry(ToolUseBlock toolUse, RegisteredAgentTool tool,
                                    AgentLoopState state, AgentLoopObserver safeObserver,
                                    long toolStartedAt) throws Exception {
        Exception lastError = null;
        AgentRecoveryDecision decision = null;
        int maxRetries = 2;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return tool.handler().handle(toolUse.input());
            } catch (Exception e) {
                lastError = e;
                decision = recoveryPolicy.toolError(toolUse, e);
                if (decision.type() != AgentRecoveryType.TRANSIENT || attempt >= maxRetries) {
                    break;
                }
                publishHook(AgentHookEventType.ON_RETRY, state.turnCount(), toolUse.name(), Map.of(
                        "attempt", attempt + 1,
                        "maxRetries", maxRetries,
                        "error", blankToPlaceholder(e.getMessage())
                ));
                try { Thread.sleep(Math.min(200L * (attempt + 1), 1000L)); } catch (InterruptedException ignored) {}
            }
        }
        // All retries exhausted or non-transient error
        long toolLatencyMs = System.currentTimeMillis() - toolStartedAt;
        safeObserver.onToolError(state.turnCount(), toolUse.name(), toolUse.input(), lastError, toolLatencyMs);
        publishHook(AgentHookEventType.ON_TOOL_ERROR, state.turnCount(), toolUse.name(), Map.of(
                "input", toolUse.input(),
                "error", blankToPlaceholder(lastError == null ? null : lastError.getMessage()),
                "latencyMs", toolLatencyMs
        ));
        applyTieredRecovery(state, toolUse, decision != null ? decision : recoveryPolicy.toolError(toolUse, lastError));
        return null;
    }

    private void applyTieredRecovery(AgentLoopState state, ToolUseBlock toolUse,
                                     AgentRecoveryDecision decision) {
        switch (decision.type()) {
            case TRANSIENT -> {
                // Retries exhausted — escalate to parameter recovery
                AgentRecoveryDecision escalated = new AgentRecoveryDecision(
                        AgentRecoveryType.PARAMETER, true,
                        "Transient error persisted after retries: " + decision.reason(),
                        Map.of(
                                "error", "transient_escalated",
                                "originalReason", decision.reason(),
                                "instruction", "The operation failed after retries. Try an alternative approach or tool."
                        ));
                applyToolRecovery(state, toolUse, escalated);
            }
            case PARAMETER -> {
                // Local ReAct fix — model gets error message, tries corrected call
                applyToolRecovery(state, toolUse, decision);
            }
            case SEMANTIC -> {
                // Global replan — wipe plan, trigger planning stage
                publishHook(AgentHookEventType.ON_REPLAN, state.turnCount(), toolUse.name(), Map.of(
                        "reason", decision.reason()
                ));
                state.setPlan(null);
                appendToolResult(state, toolUse, Map.of(
                        "error", "semantic_error",
                        "toolName", toolUse.name(),
                        "reason", decision.reason(),
                        "instruction", "The current approach is not working. Re-examine the goal and available tools. Consider a completely different strategy or return final_answer if stuck."
                ));
                state.transitionReason("tool_result");
            }
            default -> applyToolRecovery(state, toolUse, decision);
        }
    }

    private String extractJsonObject(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Model output is blank");
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Model output has no JSON object");
        }
        return value.substring(start, end + 1);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String blankToPlaceholder(String value) {
        return value == null || value.isBlank() ? "(no error message)" : value;
    }

    private void publishHook(AgentHookEventType type, int turn, String toolName, Map<String, Object> payload) {
        hookManager.publish(AgentHookEvent.of(type, turn, toolName, payload));
    }

    private void publishRecoveryHook(int turn, String toolName, AgentRecoveryDecision recoveryDecision) {
        publishHook(AgentHookEventType.ON_RECOVERY, turn, toolName, Map.of(
                "type", recoveryDecision.type(),
                "retry", recoveryDecision.retry(),
                "reason", recoveryDecision.reason()
        ));
    }

    private record AgentTurnDecision(ToolUseBlock toolUse,
                                     String finalAnswer,
                                     AgentRecoveryDecision recoveryDecision) {

        static AgentTurnDecision toolUse(ToolUseBlock toolUse) {
            return new AgentTurnDecision(toolUse, null, null);
        }

        static AgentTurnDecision finalAnswer(String finalAnswer) {
            return new AgentTurnDecision(null, finalAnswer, null);
        }

        static AgentTurnDecision recovery(AgentRecoveryDecision recoveryDecision) {
            return new AgentTurnDecision(null, null, recoveryDecision);
        }
    }
}
