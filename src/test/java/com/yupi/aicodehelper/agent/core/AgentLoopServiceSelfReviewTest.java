package com.yupi.aicodehelper.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopServiceSelfReviewTest {

    private static AgentLoopService loopServiceWithReview() {
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
                new AgentSelfReviewer(new ObjectMapper(), 1),
                null
        );
    }

    @Test
    void shouldReturnOriginalAnswerWhenReviewPasses() {
        AgentLoopService loopService = loopServiceWithReview();
        AgentToolRegistry registry = new AgentToolRegistry();

        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Explain two sum.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (prompt.contains("You are a reviewer agent")) {
                        return """
                                {"type":"review_result","verdict":"PASS"}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Use a hash table for O(n) time."}
                            """;
                },
                AgentLoopObserver.NOOP,
                5
        );

        assertThat(state.finished()).isTrue();
        assertThat(state.finalAnswer()).isEqualTo("Use a hash table for O(n) time.");
    }

    @Test
    void shouldUseRevisedAnswerWhenReviewFails() {
        AgentLoopService loopService = loopServiceWithReview();
        AgentToolRegistry registry = new AgentToolRegistry();

        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Explain two sum.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (prompt.contains("You are a reviewer agent")) {
                        return """
                                {"type":"review_result","verdict":"REVISE","revised":"Use a hash table to store seen numbers and check for complement in O(n) time."}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Sort the array and use two pointers."}
                            """;
                },
                AgentLoopObserver.NOOP,
                5
        );

        assertThat(state.finished()).isTrue();
        assertThat(state.finalAnswer()).isEqualTo(
                "Use a hash table to store seen numbers and check for complement in O(n) time.");
    }

    @Test
    void shouldNotReviewWhenSelfReviewerIsNull() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AgentToolRegistry registry = new AgentToolRegistry();

        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Explain two sum.",
                registry,
                prompt -> {
                    turn.incrementAndGet();
                    return """
                            {"type":"final_answer","content":"Use a hash table."}
                            """;
                },
                AgentLoopObserver.NOOP,
                5
        );

        assertThat(state.finished()).isTrue();
        assertThat(state.finalAnswer()).isEqualTo("Use a hash table.");
        assertThat(turn.get()).isEqualTo(1); // no extra review turn
    }
}
