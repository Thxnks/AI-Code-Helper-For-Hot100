package com.yupi.aicodehelper.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopServiceApprovalTest {

    private static AgentLoopService loopServiceWithApprovalHandler(AgentApprovalHandler handler) {
        return new AgentLoopService(
                new ObjectMapper(),
                SkillCatalogService.of(Map.of()),
                new AgentPermissionGate(),
                new AgentHookManager(),
                new AgentPromptBuilder(new ObjectMapper()),
                new AgentRecoveryPolicy(),
                new TaskGraphService(new InMemoryTaskBoard()),
                null,
                new AgentPlanValidator(),
                null,
                null,
                handler
        );
    }

    @Test
    void shouldExecuteSensitiveToolWhenApproved() {
        AgentLoopService loopService = loopServiceWithApprovalHandler((tool, toolUse) -> true);
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("deleteData", "Delete user data.", AgentToolPermissionLevel.SENSITIVE,
                        input -> Map.of("status", "deleted"));

        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Delete my old records.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_1","name":"deleteData","input":{"reason":"cleanup"}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Records deleted."}
                            """;
                },
                AgentLoopObserver.NOOP,
                AgentPermissionContext.readOnly(), // allowSensitive=false
                5
        );

        assertThat(state.finished()).isTrue();
        assertThat(state.finalAnswer()).isEqualTo("Records deleted.");
        // Tool should have executed (no permission_denied error in messages)
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("\"status\":\"deleted\""));
    }

    @Test
    void shouldDenySensitiveToolWhenNotApproved() {
        AgentLoopService loopService = loopServiceWithApprovalHandler((tool, toolUse) -> false);
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("deleteData", "Delete user data.", AgentToolPermissionLevel.SENSITIVE,
                        input -> Map.of("status", "deleted"));

        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Delete my old records.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_1","name":"deleteData","input":{"reason":"cleanup"}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Cannot delete, permission denied."}
                            """;
                },
                AgentLoopObserver.NOOP,
                AgentPermissionContext.readOnly(),
                5
        );

        assertThat(state.finished()).isTrue();
        // Tool should have been denied
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("permission_denied"));
    }

    @Test
    void shouldNotTriggerApprovalForWriteTool() {
        AtomicInteger approvalCalled = new AtomicInteger(0);
        AgentApprovalHandler handler = (tool, toolUse) -> {
            approvalCalled.incrementAndGet();
            return true;
        };
        AgentLoopService loopService = loopServiceWithApprovalHandler(handler);
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("updateProgress", "Save progress.", AgentToolPermissionLevel.WRITE,
                        input -> Map.of("saved", true));

        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Save my progress.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_1","name":"updateProgress","input":{"slug":"two-sum"}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Progress saved."}
                            """;
                },
                AgentLoopObserver.NOOP,
                AgentPermissionContext.readOnly(), // allowWrite=false
                5
        );

        assertThat(state.finished()).isTrue();
        // Approval handler should NOT have been called for WRITE tool
        assertThat(approvalCalled.get()).isEqualTo(0);
        // WRITE tool should be denied without asking for approval
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("permission_denied"));
    }

    @Test
    void shouldSkipPermissionDenyWhenSensitiveAndApproved() {
        AgentLoopService loopService = loopServiceWithApprovalHandler((tool, toolUse) -> true);
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("sendEmail", "Send email.", AgentToolPermissionLevel.SENSITIVE,
                        input -> Map.of("sent", true));

        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Send a report email.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_1","name":"sendEmail","input":{"to":"user@test.com"}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Email sent."}
                            """;
                },
                AgentLoopObserver.NOOP,
                new AgentPermissionContext(false, false, false), // all false, but approval bypasses
                5
        );

        assertThat(state.finished()).isTrue();
        assertThat(state.finalAnswer()).isEqualTo("Email sent.");
        // Should contain the successful tool result, not permission_denied
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("\"sent\":true"));
    }
}
