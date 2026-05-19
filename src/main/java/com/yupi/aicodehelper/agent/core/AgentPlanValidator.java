package com.yupi.aicodehelper.agent.core;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AgentPlanValidator {

    private static final int MAX_PLAN_STEPS = 8;
    private static final int MAX_VALIDATION_ATTEMPTS = 2;

    public record PlanValidationResult(boolean valid, List<PlanStep> steps, List<String> issues) {

        public static PlanValidationResult accept(List<PlanStep> steps) {
            return new PlanValidationResult(true, steps, List.of());
        }

        public static PlanValidationResult reject(List<String> issues, List<PlanStep> steps) {
            return new PlanValidationResult(false, steps, issues);
        }

        public String toReplanPrompt() {
            return "Your previous plan was rejected due to these issues:\n"
                    + issues.stream().map(i -> "- " + i).collect(Collectors.joining("\n"))
                    + "\n\nPlease revise the plan. Output a corrected [Plan] section.";
        }
    }

    public PlanValidationResult validate(List<PlanStep> steps, AgentToolRegistry toolRegistry) {
        if (steps == null || steps.isEmpty()) {
            return PlanValidationResult.reject(
                    List.of("Plan is empty. Provide at least one step."), List.of());
        }

        List<String> issues = new ArrayList<>();
        Set<String> availableTools = toolRegistry.specs().stream()
                .map(AgentToolSpec::name)
                .collect(Collectors.toSet());

        if (steps.size() > MAX_PLAN_STEPS) {
            issues.add("Plan has " + steps.size() + " steps, maximum is " + MAX_PLAN_STEPS);
        }

        for (int i = 0; i < steps.size(); i++) {
            PlanStep step = steps.get(i);
            if (step.action() == null || step.action().isBlank()) {
                issues.add("Step " + (i + 1) + " has no action description");
            }
            if (step.isToolStep() && !availableTools.contains(step.toolName())) {
                issues.add("Step " + (i + 1) + " references unknown tool: " + step.toolName()
                        + ". Available: " + availableTools);
            }
            if (step.order() != i) {
                issues.add("Step " + (i + 1) + " has wrong order field: expected " + i);
            }
        }

        boolean hasAtLeastOneTool = steps.stream().anyMatch(PlanStep::isToolStep);
        if (!hasAtLeastOneTool) {
            issues.add("Plan has no tool-using steps. At least one data-fetching action is required.");
        }

        if (issues.isEmpty()) {
            return PlanValidationResult.accept(steps);
        }
        return PlanValidationResult.reject(issues, steps);
    }

    public int maxAttempts() {
        return MAX_VALIDATION_ATTEMPTS;
    }
}
