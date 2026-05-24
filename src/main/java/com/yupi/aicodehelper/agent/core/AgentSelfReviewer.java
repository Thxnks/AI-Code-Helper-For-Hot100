package com.yupi.aicodehelper.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class AgentSelfReviewer {

    private final ObjectMapper objectMapper;
    private final int maxReviewTurns;

    public AgentSelfReviewer(ObjectMapper objectMapper) {
        this(objectMapper, 1);
    }

    public AgentSelfReviewer(ObjectMapper objectMapper, int maxReviewTurns) {
        this.objectMapper = objectMapper;
        this.maxReviewTurns = Math.max(1, Math.min(maxReviewTurns, 3));
    }

    public String review(String finalAnswer, AgentLoopState state, AgentTurnClient turnClient) {
        String answer = finalAnswer;
        for (int i = 0; i < maxReviewTurns; i++) {
            String reviewPrompt = buildReviewPrompt(answer, state);
            String modelOutput = turnClient.nextTurn(reviewPrompt);
            ReviewResult result = parseReview(modelOutput);
            if (result.passed()) {
                return answer;
            }
            answer = result.finalAnswer();
        }
        return answer;
    }

    private String buildReviewPrompt(String finalAnswer, AgentLoopState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a reviewer agent. Review the following answer for quality and accuracy.

                Review criteria:
                - Are claims grounded in the retrieved knowledge? Flag any hallucination.
                - Are citations ([1], [2]) pointing to the correct sources?
                - Is anything important from the retrieved knowledge missing?
                - Is the answer directly addressing the user's goal?

                """);
        sb.append("User goal: ").append(state.messages().isEmpty()
                ? "(unknown)" : truncate(state.messages().get(0).content(), 500)).append("\n\n");

        if (!state.citationRegistry().all().isEmpty()) {
            sb.append("Registered sources:\n");
            for (AgentCitationRef ref : state.citationRegistry().all()) {
                sb.append("  [").append(ref.ref()).append("] ").append(ref.source());
                if (ref.section() != null && !ref.section().isBlank()) {
                    sb.append(" > ").append(ref.section());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("Answer to review:\n").append(finalAnswer).append("\n\n");

        sb.append("""
                Output JSON only:
                {"type":"review_result","verdict":"PASS"}
                or
                {"type":"review_result","verdict":"REVISE","revised":"improved answer with corrections"}

                If the answer is accurate, complete, and grounded, return PASS.
                If there are issues, return REVISE with the full corrected answer in "revised".
                """);

        return sb.toString();
    }

    private ReviewResult parseReview(String modelOutput) {
        try {
            String json = extractJsonObject(modelOutput);
            JsonNode root = objectMapper.readTree(json);
            String verdict = root.path("verdict").asText("");
            if ("PASS".equals(verdict)) {
                return ReviewResult.pass();
            }
            String revised = root.path("revised").asText("");
            if (!revised.isBlank()) {
                return ReviewResult.revise(revised);
            }
        } catch (Exception ignored) {
        }
        return ReviewResult.pass();
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

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record ReviewResult(boolean passed, String finalAnswer) {
        static ReviewResult pass() {
            return new ReviewResult(true, null);
        }

        static ReviewResult revise(String revised) {
            return new ReviewResult(false, revised);
        }
    }
}
