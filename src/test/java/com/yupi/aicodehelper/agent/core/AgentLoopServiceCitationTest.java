package com.yupi.aicodehelper.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.aicodehelper.agent.KnowledgeSnippetView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopServiceCitationTest {

    @Test
    void shouldWrapRetrieveKnowledgeResultsWithRefIds() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("retrieveKnowledge", "Retrieve knowledge. Input: query, limit.", input ->
                        List.of(
                                new KnowledgeSnippetView("hot100/two-sum.md", "Two Sum", "两数之和",
                                        "哈希表解法", 85, List.of("hash"), "Use a hash table..."),
                                new KnowledgeSnippetView("docs/algo.md", "Algo Notes", "算法笔记",
                                        "复杂度分析", 72, List.of("complexity"), "O(n) time complexity...")
                        ));

        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "What is the optimal solution for two sum?",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_1","name":"retrieveKnowledge","input":{"query":"two sum optimal solution","limit":3}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Use a hash table for O(1) lookups [1]. Total time complexity is O(n) [2]."}
                            """;
                },
                AgentLoopObserver.NOOP,
                5
        );

        assertThat(state.finished()).isTrue();
        // Tool result should contain ref fields
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("\"ref\":1"));
        // Final answer should have reference list appended
        assertThat(state.finalAnswer()).contains("References:");
        assertThat(state.finalAnswer()).contains("[1] hot100/two-sum.md");
        assertThat(state.finalAnswer()).contains("[2] docs/algo.md");
    }

    @Test
    void shouldNotAffectNonKnowledgeTools() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("getWeakTags", "Inspect weak tags.", input ->
                        List.of(Map.of("tag", "dp"), Map.of("tag", "binary-search")));

        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Show my weak tags.",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_1","name":"getWeakTags","input":{}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Your weak tags are dp and binary-search."}
                            """;
                },
                AgentLoopObserver.NOOP,
                5
        );

        assertThat(state.finished()).isTrue();
        // Non-knowledge tool output should not have ref wrapping
        assertThat(state.messages())
                .extracting(AgentMessage::content)
                .anySatisfy(content -> assertThat(content).contains("\"tag\":\"dp\""));
        // No reference list since no citations registered
        assertThat(state.finalAnswer()).doesNotContain("References:");
    }

    @Test
    void shouldNotAppendReferencesWhenFinalAnswerHasNoCitations() {
        AgentLoopService loopService = new AgentLoopService(new ObjectMapper());
        AgentToolRegistry registry = new AgentToolRegistry()
                .register("retrieveKnowledge", "Retrieve knowledge.", input ->
                        List.of(new KnowledgeSnippetView("source.md", "content here")));

        AtomicInteger turn = new AtomicInteger();

        AgentLoopState state = loopService.run(
                "Query",
                registry,
                prompt -> {
                    int currentTurn = turn.incrementAndGet();
                    if (currentTurn == 1) {
                        return """
                                {"type":"tool_use","id":"toolu_1","name":"retrieveKnowledge","input":{"query":"test"}}
                                """;
                    }
                    return """
                            {"type":"final_answer","content":"Here is a plain answer without any citation markers."}
                            """;
                },
                AgentLoopObserver.NOOP,
                5
        );

        assertThat(state.finished()).isTrue();
        // No citation markers means reference list should not be appended
        assertThat(state.finalAnswer()).doesNotContain("References:");
    }
}
