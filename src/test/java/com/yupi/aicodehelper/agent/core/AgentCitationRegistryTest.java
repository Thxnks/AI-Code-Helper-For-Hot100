package com.yupi.aicodehelper.agent.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCitationRegistryTest {

    @Test
    void shouldAssignIncrementingRefNumbers() {
        AgentCitationRegistry registry = new AgentCitationRegistry();

        int ref1 = registry.register("source1.md", "Title 1", "Section A");
        int ref2 = registry.register("source2.md", "Title 2", "Section B");
        int ref3 = registry.register("source3.md", null, null);

        assertThat(ref1).isEqualTo(1);
        assertThat(ref2).isEqualTo(2);
        assertThat(ref3).isEqualTo(3);
        assertThat(registry.all()).hasSize(3);
    }

    @Test
    void shouldReturnAnswerUnchangedWhenNoRefsRegistered() {
        AgentCitationRegistry registry = new AgentCitationRegistry();

        String result = registry.appendReferenceList("This is a final answer.");

        assertThat(result).isEqualTo("This is a final answer.");
    }

    @Test
    void shouldReturnAnswerUnchangedWhenNull() {
        AgentCitationRegistry registry = new AgentCitationRegistry();

        String result = registry.appendReferenceList(null);

        assertThat(result).isEqualTo("");
    }

    @Test
    void shouldAppendReferenceListWhenCitationsFound() {
        AgentCitationRegistry registry = new AgentCitationRegistry();
        registry.register("hot100/two-sum.md", "Two Sum", "哈希表解法");
        registry.register("docs/algo.md", "Algorithm Notes", "复杂度分析");

        String result = registry.appendReferenceList(
                "Use a hash table for O(1) lookups [1]. Total complexity is O(n) [2]."
        );

        assertThat(result).contains("References:");
        assertThat(result).contains("[1] hot100/two-sum.md > 哈希表解法");
        assertThat(result).contains("[2] docs/algo.md > 复杂度分析");
    }

    @Test
    void shouldNotAppendReferencesWhenNoCitationBracketsInAnswer() {
        AgentCitationRegistry registry = new AgentCitationRegistry();
        registry.register("source.md", "Title", "Section");

        String result = registry.appendReferenceList("Plain answer without any citations.");

        assertThat(result).doesNotContain("References:");
        assertThat(result).isEqualTo("Plain answer without any citations.");
    }

    @Test
    void shouldHandleUnregisteredRefNumbersGracefully() {
        AgentCitationRegistry registry = new AgentCitationRegistry();
        registry.register("source.md", "Title", "Section");

        String result = registry.appendReferenceList("Some claim [1]. Another claim [99].");

        assertThat(result).contains("References:");
        assertThat(result).contains("[1] source.md");
        // [99] in text is left as-is, reference list only shows registered refs
    }

    @Test
    void shouldHandleSectionBeingNull() {
        AgentCitationRegistry registry = new AgentCitationRegistry();
        registry.register("source.md", "Title", null);

        String result = registry.appendReferenceList("Claim [1] here.");

        assertThat(result).contains("[1] source.md");
        assertThat(result).doesNotContain("> null");
    }
}
