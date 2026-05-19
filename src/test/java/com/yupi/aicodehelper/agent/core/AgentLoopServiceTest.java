package com.yupi.aicodehelper.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopServiceTest {

    @Test
    void shouldFeedToolResultBackIntoFollowingModelTurn() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getWeakTags", "Inspect weak tags.", input -> Map.of("tag", "dynamic-programming"))
                .register("recommendNext", "Recommend next problems.", input -> "coin-change");

        AtomicInteger turn = new AtomicInteger();
        AtomicInteger promptsThatSawToolResult = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Recommend my next Hot100 problem.",
                registry,
                prompt -> {
                    if (prompt.contains("\"type\":\"tool_result\"")) {
                        promptsThatSawToolResult.incrementAndGet();
                    }
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_1","name":"getWeakTags","input":{}}
                                """;
                    }
                    if (currentTurn == 2) {
                        return """
                                {"type":"tool_use","id":"toolu_2","name":"recommendNext","input":{"limit":1}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Practice coin-change next."}
                            """;
                },
                AgentLoopObserver.NOOP,
                5
        );

        assertThat(state.finished()).isTrue();
        assertThat(state.finalAnswer()).isEqualTo("Practice coin-change next.");
        assertThat(promptsThatSawToolResult.get()).isEqualTo(2);
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("getWeakTags", "dynamic-programming"))
                .anySatisfy(content -> assertThat(content).contains("recommendNext", "coin-change"));
    }

    @Test
    void shouldExposeTodoStateAfterTodoWrite() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AgentToolRegistry registry = new AgentToolRegistry();

        AtomicInteger turn = new AtomicInteger();
        AtomicInteger promptsThatSawTodoState = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Create a short study plan.",
                registry,
                prompt -> {
                    if (prompt.contains("Check weak tags") && prompt.contains("IN_PROGRESS")) {
                        promptsThatSawTodoState.incrementAndGet();
                    }
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_todo","name":"todo_write","input":{"todos":[{"content":"Check weak tags","status":"IN_PROGRESS"},{"content":"Recommend next problem","status":"PENDING"}]}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Todo state is available."}
                            """;
                },
                AgentLoopObserver.NOOP,
                3
        );

        assertThat(state.finished()).isTrue();
        assertThat(state.todos())
                .containsExactly(
                        new TodoItem("Check weak tags", TodoStatus.IN_PROGRESS),
                        new TodoItem("Recommend next problem", TodoStatus.PENDING)
                );
        assertThat(promptsThatSawTodoState.get()).isEqualTo(1);
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("todo_write", "Check weak tags"));
    }

    @Test
    void shouldLoadSkillThroughCoreTool() {
        AgentLoopService loopService = new AgentLoopService(
                new ObjectMapper(),
                SkillCatalogService.of(Map.of("test-skill", "# Test Skill\n\nUse this for tests."))
        );
        AgentToolRegistry registry = new AgentToolRegistry();
        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Load a skill.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_skill","name":"load_skill","input":{"name":"test-skill"}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Loaded the test skill."}
                            """;
                },
                AgentLoopObserver.NOOP,
                3
        );

        assertThat(state.finalAnswer()).isEqualTo("Loaded the test skill.");
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("test-skill", "Test Skill"));
    }

    @Test
    void shouldCompactLongCurrentRunContext() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("observe", "Return one observation.", input -> "observation-" + input.get("index"));
        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Collect several observations.",
                registry,
                prompt -> {
                    if (prompt.contains("Summarize this agent conversation")) {
                        return """
                                {"goal":"Collect observations","done":"Observed several tool results","findings":["Compaction was triggered"],"remaining":"Finish the run"}
                                """;
                    }
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn <= 14) {
                        return """
                                {"type":"tool_use","id":"toolu_%s","name":"observe","input":{"index":%s}}
                                """.formatted(currentTurn, currentTurn);
                    }
                    return """
                            {"type":"final_answer","content":"Compacted and finished."}
                            """;
                },
                AgentLoopObserver.NOOP,
                18
        );

        assertThat(state.finalAnswer()).isEqualTo("Compacted and finished.");
        assertThat(state.messages().size()).isLessThanOrEqualTo(16);
    }

    @Test
    void shouldRunSubAgentWithIndependentContext() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        SubAgentService subAgentService = new SubAgentService(loopService);
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("inspect", "Inspect subtask data.", input -> "sub-data");
        AtomicInteger turn = new AtomicInteger();

        SubAgentResult result = subAgentService.run(
                "Inspect data for parent.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_sub","name":"inspect","input":{}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Sub-agent summary."}
                            """;
                },
                3
        );

        assertThat(result.summary()).isEqualTo("Sub-agent summary.");
        assertThat(result.turns()).isEqualTo(2);
    }

    @Test
    void shouldDenyWriteToolWithoutPermission() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AtomicInteger turn = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();
        AgentToolRegistry guardedRegistry = new AgentToolRegistry()
                .register("writeProgress", "Write progress.", AgentToolPermissionLevel.WRITE, input -> {
                    handlerCalls.incrementAndGet();
                    return "saved";
                });

        AgentLoopState state = loopService.run(
                "Save my progress.",
                guardedRegistry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_write","name":"writeProgress","input":{}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Write was denied."}
                            """;
                },
                AgentLoopObserver.NOOP,
                AgentPermissionContext.readOnly(),
                3
        );

        assertThat(state.finalAnswer()).isEqualTo("Write was denied.");
        assertThat(handlerCalls.get()).isZero();
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("permission_denied", "WRITE"));
    }

    @Test
    void shouldAllowWriteToolWithPermission() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AtomicInteger handlerCalls = new AtomicInteger();
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("writeProgress", "Write progress.", AgentToolPermissionLevel.WRITE, input -> {
                    handlerCalls.incrementAndGet();
                    return "saved";
                });
        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Save my progress.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_write","name":"writeProgress","input":{}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Write succeeded."}
                            """;
                },
                AgentLoopObserver.NOOP,
                new AgentPermissionContext(true, false, false),
                3
        );

        assertThat(state.finalAnswer()).isEqualTo("Write succeeded.");
        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("writeProgress", "saved"));
    }

    @Test
    void shouldPublishHooksWithoutChangingMainFlow() {
        List<AgentHookEvent> events = new ArrayList<>();
        AgentHookManager hookManager = new AgentHookManager(List.of(
                events::add,
                event -> {
                    throw new IllegalStateException("hook should not break loop");
                }
        ));
        AgentLoopService loopService = new AgentLoopService(
                new ObjectMapper(),
                SkillCatalogService.of(Map.of()),
                new AgentPermissionGate(),
                hookManager
        );
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("inspect", "Inspect data.", input -> "inspected");
        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Inspect something.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_inspect","name":"inspect","input":{}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Done."}
                            """;
                },
                AgentLoopObserver.NOOP,
                3
        );

        assertThat(state.finalAnswer()).isEqualTo("Done.");
        assertThat(events)
                .extracting(AgentHookEvent::type)
                .contains(
                        AgentHookEventType.BEFORE_MODEL_TURN,
                        AgentHookEventType.AFTER_MODEL_TURN,
                        AgentHookEventType.BEFORE_TOOL_CALL,
                        AgentHookEventType.AFTER_TOOL_CALL
                );
        assertThat(events)
                .filteredOn(event -> event.type() == AgentHookEventType.BEFORE_TOOL_CALL)
                .singleElement()
                .satisfies(event -> assertThat(event.toolName()).isEqualTo("inspect"));
    }

    @Test
    void shouldPublishPermissionDeniedHook() {
        List<AgentHookEvent> events = new ArrayList<>();
        AgentLoopService loopService = new AgentLoopService(
                new ObjectMapper(),
                SkillCatalogService.of(Map.of()),
                new AgentPermissionGate(),
                new AgentHookManager(List.of(events::add))
        );
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("writeProgress", "Write progress.", AgentToolPermissionLevel.WRITE, input -> "saved");
        AtomicInteger turn = new AtomicInteger();

        loopService.run(
                "Save progress.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_write","name":"writeProgress","input":{}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Denied."}
                            """;
                },
                AgentLoopObserver.NOOP,
                AgentPermissionContext.readOnly(),
                3
        );

        assertThat(events)
                .filteredOn(event -> event.type() == AgentHookEventType.ON_PERMISSION_DENIED)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.toolName()).isEqualTo("writeProgress");
                    assertThat(event.payload()).containsEntry("reason", "WRITE tool requires allowWrite=true");
                });
    }

    @Test
    void shouldRecoverFromInvalidModelOutputAndRetry() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AgentToolRegistry registry = new AgentToolRegistry();
        AtomicInteger turn = new AtomicInteger();
        AtomicInteger promptsThatSawRecovery = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Give a valid answer.",
                registry,
                prompt -> {
                    if (prompt.contains("invalid_model_output")) {
                        promptsThatSawRecovery.incrementAndGet();
                    }
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return "not json";
                    }
                    return """
                            {"type":"final_answer","content":"Recovered."}
                            """;
                },
                AgentLoopObserver.NOOP,
                3
        );

        assertThat(state.finalAnswer()).isEqualTo("Recovered.");
        assertThat(promptsThatSawRecovery.get()).isEqualTo(1);
        assertThat(state.transitionReason()).isNull();
    }

    @Test
    void shouldRecoverFromUnknownToolWithStructuredToolResult() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AgentToolRegistry registry = new AgentToolRegistry();
        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Use a tool.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_missing","name":"missingTool","input":{}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Used fallback."}
                            """;
                },
                AgentLoopObserver.NOOP,
                3
        );

        assertThat(state.finalAnswer()).isEqualTo("Used fallback.");
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("unknown_tool", "missingTool"));
    }

    @Test
    void shouldRecoverFromToolErrorWithStructuredToolResultAndHook() {
        List<AgentHookEvent> events = new ArrayList<>();
        AgentLoopService loopService = new AgentLoopService(
                new ObjectMapper(),
                SkillCatalogService.of(Map.of()),
                new AgentPermissionGate(),
                new AgentHookManager(List.of(events::add))
        );
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("unstableTool", "Can fail.", input -> {
                    throw new IllegalStateException("temporary failure");
                });
        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Handle a failing tool.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_fail","name":"unstableTool","input":{}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Recovered from tool error."}
                            """;
                },
                AgentLoopObserver.NOOP,
                3
        );

        assertThat(state.finalAnswer()).isEqualTo("Recovered from tool error.");
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("parameter_error", "temporary failure"));
        assertThat(events)
                .filteredOn(event -> event.type() == AgentHookEventType.ON_RECOVERY)
                .anySatisfy(event -> {
                    assertThat(event.toolName()).isEqualTo("unstableTool");
                    assertThat(event.payload()).containsEntry("type", AgentRecoveryType.PARAMETER);
                });
    }

    @Test
    void shouldUseRecoveryPolicyWhenMaxTurnsReached() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("observe", "Observe data.", input -> "data");

        AgentLoopState state = loopService.run(
                "Never finish.",
                registry,
                prompt -> """
                        {"type":"tool_use","id":"toolu_loop","name":"observe","input":{}}
                        """,
                AgentLoopObserver.NOOP,
                2
        );

        assertThat(state.finalAnswer()).isEqualTo("Agent stopped after reaching the maximum turn limit (2).");
    }

    @Test
    void shouldUnlockBlockedTaskAfterDependencyCompleted() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AgentToolRegistry registry = new AgentToolRegistry();
        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Create and advance persistent tasks.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_task_1","name":"task_create","input":{"subject":"Write parser"}}
                                """;
                    }
                    if (currentTurn == 2) {
                        return """
                                {"type":"tool_use","id":"toolu_task_2","name":"task_create","input":{"subject":"Write semantic check","blockedBy":[1]}}
                                """;
                    }
                    if (currentTurn == 3) {
                        return """
                                {"type":"tool_use","id":"toolu_task_3","name":"task_update","input":{"id":1,"status":"COMPLETED"}}
                                """;
                    }
                    if (currentTurn == 4) {
                        return """
                                {"type":"tool_use","id":"toolu_task_4","name":"task_get","input":{"id":2}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Task #2 is unblocked and ready."}
                            """;
                },
                AgentLoopObserver.NOOP,
                5
        );

        assertThat(state.finalAnswer()).isEqualTo("Task #2 is unblocked and ready.");
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("\"id\":2", "\"ready\":true", "\"blockedBy\":[]"));
    }
}
