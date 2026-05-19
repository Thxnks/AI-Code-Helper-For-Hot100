package com.yupi.aicodehelper.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.aicodehelper.agent.core.AgentLoopService;
import com.yupi.aicodehelper.agent.core.AgentToolRegistry;
import com.yupi.aicodehelper.agent.core.RuntimeTaskService;
import com.yupi.aicodehelper.agent.core.SkillCatalogService;
import com.yupi.aicodehelper.ai.AiCodeHelperService;
import com.yupi.aicodehelper.entity.AgentStep;
import com.yupi.aicodehelper.entity.AgentTask;
import com.yupi.aicodehelper.repository.AgentStepRepository;
import com.yupi.aicodehelper.repository.AgentTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Hot100AgentServiceTest {

    @Test
    void shouldRunLoopAndPersistTraceSteps() {
        ObjectMapper objectMapper = new ObjectMapper();
        AiCodeHelperService aiCodeHelperService = mock(AiCodeHelperService.class);
        Hot100AgentToolRegistry toolRegistry = mock(Hot100AgentToolRegistry.class);
        AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
        AgentStepRepository stepRepository = mock(AgentStepRepository.class);
        AgentLoopService loopService = new AgentLoopService(objectMapper, SkillCatalogService.of(java.util.Map.of()));
        Executor directExecutor = Runnable::run;

        List<AgentTask> tasks = new ArrayList<>();
        List<AgentStep> steps = new ArrayList<>();

        when(taskRepository.save(any(AgentTask.class))).thenAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            tasks.removeIf(existing -> existing.getTaskId().equals(task.getTaskId()));
            tasks.add(task);
            return task;
        });
        when(taskRepository.findByUserIdAndTaskId(anyLong(), anyString())).thenAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            String taskId = invocation.getArgument(1);
            return tasks.stream()
                    .filter(task -> task.getUserId().equals(userId) && task.getTaskId().equals(taskId))
                    .findFirst();
        });
        when(stepRepository.save(any(AgentStep.class))).thenAnswer(invocation -> {
            AgentStep step = invocation.getArgument(0);
            steps.add(step);
            return step;
        });
        when(stepRepository.findByTaskIdOrderByStepOrderAsc(anyString())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            return steps.stream()
                    .filter(step -> step.getTaskId().equals(taskId))
                    .sorted(Comparator.comparing(AgentStep::getStepOrder))
                    .toList();
        });
        when(stepRepository.findByTaskIdAndRuntimeIdOrderByStepOrderAsc(anyString(), anyString())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            String runtimeId = invocation.getArgument(1);
            return steps.stream()
                    .filter(step -> step.getTaskId().equals(taskId) && runtimeId.equals(step.getRuntimeId()))
                    .sorted(Comparator.comparing(AgentStep::getStepOrder))
                    .toList();
        });

        when(toolRegistry.create(any(Hot100AgentRunRequest.class), anyLong()))
                .thenReturn(new AgentToolRegistry());

        AtomicInteger turn = new AtomicInteger();
        when(aiCodeHelperService.runHot100AgentLoopTurn(anyString())).thenAnswer(invocation -> {
            int currentTurn = turn.incrementAndGet();
            if (currentTurn == 1) {
                return """
                        {"type":"plan","steps":[{"action":"Create todo list to organize work","toolName":"todo_write","rationale":"track tasks","order":0}]}
                        """;
            }
            if (currentTurn == 2) {
                return """
                        {"type":"tool_use","id":"toolu_todo","name":"todo_write","input":{"todos":[{"content":"Check weak tags","status":"IN_PROGRESS"}]}}
                        """;
            }
            return """
                    {"type":"final_answer","content":"Ready to practice."}
                    """;
        });

        Hot100AgentService service = new Hot100AgentService(
                aiCodeHelperService,
                loopService,
                toolRegistry,
                taskRepository,
                stepRepository,
                objectMapper,
                new RuntimeTaskService(directExecutor),
                directExecutor
        );

        AgentTaskView view = service.run(new Hot100AgentRunRequest(
                "Help me plan Hot100 practice",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ), 1L);

        assertThat(view.status()).isEqualTo(AgentTaskStatus.SUCCESS.name());
        assertThat(view.finalAnswer()).isEqualTo("Ready to practice.");
        assertThat(view.steps())
                .extracting(AgentStepView::toolName)
                .containsExactly("model_turn", "tool:todo_write", "model_turn");
        assertThat(view.steps().get(1).toolOutput()).contains("Check weak tags", "IN_PROGRESS");
        assertThat(taskRepository.findByUserIdAndTaskId(1L, view.taskId())).isPresent();
        assertThat(stepRepository.findByTaskIdOrderByStepOrderAsc(view.taskId())).hasSize(3);
    }

    @Test
    void shouldExposeRuntimeStateForSubmittedAgentTask() {
        ObjectMapper objectMapper = new ObjectMapper();
        AiCodeHelperService aiCodeHelperService = mock(AiCodeHelperService.class);
        Hot100AgentToolRegistry toolRegistry = mock(Hot100AgentToolRegistry.class);
        AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
        AgentStepRepository stepRepository = mock(AgentStepRepository.class);
        AgentLoopService loopService = new AgentLoopService(objectMapper, SkillCatalogService.of(java.util.Map.of()));
        Executor directExecutor = Runnable::run;

        List<AgentTask> tasks = new ArrayList<>();
        List<AgentStep> steps = new ArrayList<>();

        when(taskRepository.save(any(AgentTask.class))).thenAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            tasks.removeIf(existing -> existing.getTaskId().equals(task.getTaskId()));
            tasks.add(task);
            return task;
        });
        when(taskRepository.findByUserIdAndTaskId(anyLong(), anyString())).thenAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            String taskId = invocation.getArgument(1);
            return tasks.stream()
                    .filter(task -> task.getUserId().equals(userId) && task.getTaskId().equals(taskId))
                    .findFirst();
        });
        when(stepRepository.save(any(AgentStep.class))).thenAnswer(invocation -> {
            AgentStep step = invocation.getArgument(0);
            steps.add(step);
            return step;
        });
        when(stepRepository.findByTaskIdOrderByStepOrderAsc(anyString())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            return steps.stream()
                    .filter(step -> step.getTaskId().equals(taskId))
                    .sorted(Comparator.comparing(AgentStep::getStepOrder))
                    .toList();
        });
        when(stepRepository.findByTaskIdAndRuntimeIdOrderByStepOrderAsc(anyString(), anyString())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            String runtimeId = invocation.getArgument(1);
            return steps.stream()
                    .filter(step -> step.getTaskId().equals(taskId) && runtimeId.equals(step.getRuntimeId()))
                    .sorted(Comparator.comparing(AgentStep::getStepOrder))
                    .toList();
        });
        when(toolRegistry.create(any(Hot100AgentRunRequest.class), anyLong()))
                .thenReturn(new AgentToolRegistry());
        when(aiCodeHelperService.runHot100AgentLoopTurn(anyString())).thenReturn("""
                {"type":"final_answer","content":"Submitted task finished."}
                """);

        Hot100AgentService service = new Hot100AgentService(
                aiCodeHelperService,
                loopService,
                toolRegistry,
                taskRepository,
                stepRepository,
                objectMapper,
                new RuntimeTaskService(directExecutor),
                directExecutor
        );

        AgentTaskView view = service.submit(new Hot100AgentRunRequest(
                "Run in background",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ), 1L);

        assertThat(view.status()).isEqualTo(AgentTaskStatus.SUCCESS.name());
        assertThat(view.latestRuntime()).isNotNull();
        assertThat(view.latestRuntime().status()).isEqualTo("SUCCESS");
        assertThat(view.latestRuntime().stage()).isEqualTo("finished");
        assertThat(view.latestRuntime().taskId()).isEqualTo(view.taskId());
        assertThat(view.latestRuntime().attempt()).isEqualTo(1);
        assertThat(view.latestRuntime().progress()).isEqualTo(100);
        assertThat(view.runtimeHistory()).hasSize(1);
        assertThat(view.steps())
                .extracting(AgentStepView::runtimeId)
                .containsOnly(view.latestRuntime().runtimeId());
        assertThat(service.listRuntimeSteps(view.taskId(), view.latestRuntime().runtimeId(), 1L))
                .extracting(AgentStepView::runtimeId)
                .containsOnly(view.latestRuntime().runtimeId());
        AgentTraceView trace = service.getTrace(view.taskId(), 1L);
        assertThat(trace.taskId()).isEqualTo(view.taskId());
        assertThat(trace.latestRuntime().runtimeId()).isEqualTo(view.latestRuntime().runtimeId());
        assertThat(trace.runtimes()).hasSize(1);
        assertThat(trace.summary().runtimeAttempts()).isEqualTo(1);
        assertThat(trace.summary().totalSteps()).isEqualTo(1);
        assertThat(trace.summary().modelTurns()).isEqualTo(1);
        assertThat(trace.summary().toolCalls()).isZero();
        assertThat(trace.timeline())
                .extracting(AgentTraceEventView::type)
                .contains("RUNTIME_CREATED", "RUNTIME_STARTED", "RUNTIME_HEARTBEAT", "RUNTIME_FINISHED", "STEP");
        assertThat(view.finalAnswer()).isEqualTo("Submitted task finished.");
    }

    @Test
    void shouldKeepSubmittedAgentTaskQueuedUntilRuntimeSlotStarts() {
        ObjectMapper objectMapper = new ObjectMapper();
        AiCodeHelperService aiCodeHelperService = mock(AiCodeHelperService.class);
        Hot100AgentToolRegistry toolRegistry = mock(Hot100AgentToolRegistry.class);
        AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
        AgentStepRepository stepRepository = mock(AgentStepRepository.class);
        AgentLoopService loopService = new AgentLoopService(objectMapper, SkillCatalogService.of(java.util.Map.of()));
        Executor pausedExecutor = command -> {
        };

        List<AgentTask> tasks = new ArrayList<>();
        when(taskRepository.save(any(AgentTask.class))).thenAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            tasks.removeIf(existing -> existing.getTaskId().equals(task.getTaskId()));
            tasks.add(task);
            return task;
        });
        when(taskRepository.findByUserIdAndTaskId(anyLong(), anyString())).thenAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            String taskId = invocation.getArgument(1);
            return tasks.stream()
                    .filter(task -> task.getUserId().equals(userId) && task.getTaskId().equals(taskId))
                    .findFirst();
        });
        when(stepRepository.findByTaskIdOrderByStepOrderAsc(anyString())).thenReturn(List.of());

        Hot100AgentService service = new Hot100AgentService(
                aiCodeHelperService,
                loopService,
                toolRegistry,
                taskRepository,
                stepRepository,
                objectMapper,
                new RuntimeTaskService(pausedExecutor),
                pausedExecutor
        );

        AgentTaskView view = service.submit(new Hot100AgentRunRequest(
                "Queue background run",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ), 1L);

        assertThat(view.status()).isEqualTo(AgentTaskStatus.QUEUED.name());
        assertThat(view.latestRuntime()).isNotNull();
        assertThat(view.latestRuntime().status()).isEqualTo("PENDING");
        assertThat(view.latestRuntime().stage()).isEqualTo("queued");
        assertThat(view.runtimeHistory()).hasSize(1);
    }
}
