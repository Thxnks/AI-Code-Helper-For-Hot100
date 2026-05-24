package com.yupi.aicodehelper.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.aicodehelper.ai.AiCodeHelperService;
import com.yupi.aicodehelper.agent.core.AgentLoopObserver;
import com.yupi.aicodehelper.agent.core.AgentLoopService;
import com.yupi.aicodehelper.agent.core.AgentLoopState;
import com.yupi.aicodehelper.agent.core.AgentPermissionContext;
import com.yupi.aicodehelper.agent.core.AgentStreamEvent;
import com.yupi.aicodehelper.agent.core.AgentToolRegistry;
import com.yupi.aicodehelper.agent.core.RuntimeTaskService;
import com.yupi.aicodehelper.common.ErrorCode;
import com.yupi.aicodehelper.entity.AgentStep;
import com.yupi.aicodehelper.entity.AgentTask;
import com.yupi.aicodehelper.exception.BusinessException;
import com.yupi.aicodehelper.repository.AgentStepRepository;
import com.yupi.aicodehelper.repository.AgentTaskRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

@Service
public class Hot100AgentService {

    private final AiCodeHelperService aiCodeHelperService;
    private final AgentLoopService agentLoopService;
    private final Hot100AgentToolRegistry hot100AgentToolRegistry;
    private final AgentTaskRepository agentTaskRepository;
    private final AgentStepRepository agentStepRepository;
    private final ObjectMapper objectMapper;
    private final RuntimeTaskService runtimeTaskService;
    private final Executor executor;
    private final ConcurrentMap<String, Sinks.Many<AgentStreamEvent>> sinksByTaskId = new ConcurrentHashMap<>();

    public Hot100AgentService(AiCodeHelperService aiCodeHelperService,
                              AgentLoopService agentLoopService,
                              Hot100AgentToolRegistry hot100AgentToolRegistry,
                              AgentTaskRepository agentTaskRepository,
                              AgentStepRepository agentStepRepository,
                              ObjectMapper objectMapper,
                              RuntimeTaskService runtimeTaskService,
                              @Qualifier("hot100TaskExecutor") Executor executor) {
        this.aiCodeHelperService = aiCodeHelperService;
        this.agentLoopService = agentLoopService;
        this.hot100AgentToolRegistry = hot100AgentToolRegistry;
        this.agentTaskRepository = agentTaskRepository;
        this.agentStepRepository = agentStepRepository;
        this.objectMapper = objectMapper;
        this.runtimeTaskService = runtimeTaskService;
        this.executor = executor;
    }

    public AgentTaskView run(Hot100AgentRunRequest request, Long userId) {
        AgentTask task = createTask(request, userId, AgentTaskStatus.QUEUED);
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        sinksByTaskId.put(task.getTaskId(), sink);
        executor.execute(() -> runAgentLoopAsync(task.getTaskId(), null, request, userId, sink));
        return toView(task);
    }

    public AgentTaskView submit(Hot100AgentRunRequest request, Long userId) {
        AgentTask task = createTask(request, userId, AgentTaskStatus.QUEUED);
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        sinksByTaskId.put(task.getTaskId(), sink);
        runtimeTaskService.submit(task.getTaskId(), "hot100-agent", slot -> {
            runtimeTaskService.heartbeat(slot.getRuntimeId(), "agent_loop", 25);
            runAgentLoopAsync(task.getTaskId(), slot.getRuntimeId(), request, userId, sink);
        });
        return toView(task);
    }

    private void runAgentLoopAsync(String taskId, String runtimeId,
                                   Hot100AgentRunRequest request, Long userId,
                                   Sinks.Many<AgentStreamEvent> sink) {
        try {
            AgentTask task = agentTaskRepository.findByUserIdAndTaskId(userId, taskId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent task not found"));
            task.setStatus(AgentTaskStatus.RUNNING.name());
            task.setErrorMessage(null);
            agentTaskRepository.save(task);

            AgentRunContext context = new AgentRunContext(taskId, runtimeId, request, userId);
            AgentLoopObserver sseObserver = createSseObserver(sink);

            String finalAnswer = executePlan(context, sseObserver);

            task.setStatus(AgentTaskStatus.SUCCESS.name());
            task.setFinalAnswer(finalAnswer);
            task.setErrorMessage(null);
            agentTaskRepository.save(task);

            sink.tryEmitNext(new AgentStreamEvent("finish", 0, null,
                    truncate(finalAnswer, 6000), 0, AgentTaskStatus.SUCCESS.name()));
        } catch (Exception e) {
            agentTaskRepository.findByUserIdAndTaskId(userId, taskId).ifPresent(t -> {
                t.setStatus(AgentTaskStatus.FAILED.name());
                t.setErrorMessage(truncate(e.getMessage(), 1000));
                agentTaskRepository.save(t);
            });
            sink.tryEmitNext(new AgentStreamEvent("error", 0, null,
                    truncate(e.getMessage(), 1000), 0, AgentTaskStatus.FAILED.name()));
        }
        sink.tryEmitComplete();
        sinksByTaskId.remove(taskId);
    }

    private AgentLoopObserver createSseObserver(Sinks.Many<AgentStreamEvent> sink) {
        return new AgentLoopObserver() {
            @Override
            public void onModelTurn(int turn, String input, String output, long latencyMs) {
                sink.tryEmitNext(new AgentStreamEvent("model_turn", turn, null,
                        truncate(input, 3000), latencyMs, AgentStepStatus.SUCCESS.name()));
            }

            @Override
            public void onToolResult(int turn, String toolName, Map<String, Object> input, Object output, long latencyMs) {
                sink.tryEmitNext(new AgentStreamEvent("tool_result", turn, toolName,
                        truncate(toJson(output), 3000), latencyMs, AgentStepStatus.SUCCESS.name()));
            }

            @Override
            public void onToolError(int turn, String toolName, Map<String, Object> input, Exception error, long latencyMs) {
                sink.tryEmitNext(new AgentStreamEvent("tool_error", turn, toolName,
                        truncate(error.getMessage(), 1000), latencyMs, AgentStepStatus.FAILED.name()));
            }
        };
    }

    public Flux<ServerSentEvent<String>> getStream(String taskId, Long userId) {
        agentTaskRepository.findByUserIdAndTaskId(userId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent task not found"));
        Sinks.Many<AgentStreamEvent> sink = sinksByTaskId.get(taskId);
        if (sink == null) {
            return Flux.empty();
        }
        return sink.asFlux()
                .map(event -> ServerSentEvent.<String>builder()
                        .id(UUID.randomUUID().toString())
                        .event(event.type())
                        .data(toJson(event))
                        .build());
    }

    @Transactional(readOnly = true)
    public AgentTaskView getTask(String taskId, Long userId) {
        AgentTask task = agentTaskRepository.findByUserIdAndTaskId(userId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent task not found"));
        return toView(task);
    }

    @Transactional(readOnly = true)
    public List<AgentStepView> listSteps(String taskId, Long userId) {
        agentTaskRepository.findByUserIdAndTaskId(userId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent task not found"));
        return agentStepRepository.findByTaskIdOrderByStepOrderAsc(taskId).stream()
                .map(AgentStepView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AgentStepView> listRuntimeSteps(String taskId, String runtimeId, Long userId) {
        agentTaskRepository.findByUserIdAndTaskId(userId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent task not found"));
        boolean runtimeBelongsToTask = runtimeTaskService.listByTaskId(taskId).stream()
                .anyMatch(slot -> slot.getRuntimeId().equals(runtimeId));
        if (!runtimeBelongsToTask) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Runtime slot not found");
        }
        return agentStepRepository.findByTaskIdAndRuntimeIdOrderByStepOrderAsc(taskId, runtimeId).stream()
                .map(AgentStepView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentTraceView getTrace(String taskId, Long userId) {
        AgentTask task = agentTaskRepository.findByUserIdAndTaskId(userId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent task not found"));
        List<RuntimeSlotView> runtimes = runtimeTaskService.listByTaskId(taskId).stream()
                .map(RuntimeSlotView::from)
                .toList();
        RuntimeSlotView latestRuntime = runtimeTaskService.getLatestByTaskId(taskId)
                .map(RuntimeSlotView::from)
                .orElse(null);
        List<AgentStepView> steps = agentStepRepository.findByTaskIdOrderByStepOrderAsc(taskId).stream()
                .map(AgentStepView::from)
                .toList();
        return AgentTraceView.from(task, latestRuntime, runtimes, buildTraceSummary(runtimes, steps),
                buildTraceTimeline(runtimes, steps));
    }

    private AgentTask createTask(Hot100AgentRunRequest request, Long userId, AgentTaskStatus status) {
        AgentTask task = new AgentTask();
        task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        task.setUserId(userId);
        task.setGoal(request.goal().trim());
        task.setStatus(status.name());
        return agentTaskRepository.save(task);
    }

    private String executePlan(AgentRunContext context) {
        return executePlan(context, AgentLoopObserver.NOOP);
    }

    private String executePlan(AgentRunContext context, AgentLoopObserver extraObserver) {
        AgentToolRegistry toolRegistry = hot100AgentToolRegistry.create(context.request(), context.userId());
        AgentLoopObserver observer = new AgentLoopObserver() {
            @Override
            public void onModelTurn(int turn, String input, String output, long latencyMs) {
                heartbeat(context);
                saveStep(context, "model_turn", Map.of("turn", turn, "input", truncate(input, 12000)),
                        output, AgentStepStatus.SUCCESS.name(), latencyMs, null);
            }

            @Override
            public void onToolResult(int turn, String toolName, Map<String, Object> input, Object output, long latencyMs) {
                heartbeat(context);
                saveStep(context, "tool:" + toolName, input, output, AgentStepStatus.SUCCESS.name(), latencyMs, null);
            }

            @Override
            public void onToolError(int turn, String toolName, Map<String, Object> input, Exception error, long latencyMs) {
                heartbeat(context);
                saveStep(context, "tool:" + toolName, input, null, AgentStepStatus.FAILED.name(), latencyMs,
                        truncate(error.getMessage(), 1000));
            }
        };
        AgentLoopObserver composite = compose(observer, extraObserver);

        AgentLoopState state = agentLoopService.run(
                buildLoopGoal(context.request()),
                toolRegistry,
                aiCodeHelperService::runHot100AgentLoopTurn,
                composite,
                new AgentPermissionContext(
                        Boolean.TRUE.equals(context.request().allowWrite()),
                        Boolean.TRUE.equals(context.request().allowExternal()),
                        false
                ),
                context.taskId()
        );
        return state.finalAnswer();
    }

    private String buildLoopGoal(Hot100AgentRunRequest request) {
        return """
                User goal:
                %s

                Optional runtime fields:
                - problemSlug: %s
                - status: %s
                - limit: %s
                - days: %s
                - hasUserCode: %s
                - hasErrorDescription: %s
                - notes: %s
                """.formatted(
                request.goal(),
                blankToNullText(request.problemSlug()),
                blankToNullText(request.status()),
                request.limit(),
                request.days(),
                !isBlank(request.userCode()),
                !isBlank(request.errorDescription()),
                blankToNullText(request.notes())
        ).trim();
    }

    private void saveStep(AgentRunContext context,
                          String toolName,
                          Map<String, Object> input,
                          Object output,
                          String status,
                          long latencyMs,
                          String errorMessage) {
        AgentStep step = new AgentStep();
        step.setTaskId(context.taskId());
        step.setRuntimeId(context.runtimeId());
        step.setStepOrder(context.nextStepOrder());
        step.setToolName(toolName);
        step.setToolInput(truncate(toJson(input), 12000));
        step.setToolOutput(truncate(toJson(output), 12000));
        step.setStatus(status);
        step.setLatencyMs(latencyMs);
        step.setErrorMessage(errorMessage);
        agentStepRepository.save(step);
    }

    private AgentTaskView toView(AgentTask task) {
        List<AgentStepView> steps = agentStepRepository.findByTaskIdOrderByStepOrderAsc(task.getTaskId()).stream()
                .map(AgentStepView::from)
                .toList();
        RuntimeSlotView latestRuntime = runtimeTaskService.getLatestByTaskId(task.getTaskId())
                .map(RuntimeSlotView::from)
                .orElse(null);
        List<RuntimeSlotView> runtimeHistory = runtimeTaskService.listByTaskId(task.getTaskId()).stream()
                .map(RuntimeSlotView::from)
                .toList();
        return AgentTaskView.from(task, latestRuntime, runtimeHistory, steps);
    }

    private AgentTraceSummaryView buildTraceSummary(List<RuntimeSlotView> runtimes, List<AgentStepView> steps) {
        int failedRuntimes = (int) runtimes.stream()
                .filter(runtime -> "FAILED".equalsIgnoreCase(runtime.status()))
                .count();
        int modelTurns = (int) steps.stream()
                .filter(step -> "model_turn".equals(step.toolName()))
                .count();
        int toolCalls = (int) steps.stream()
                .filter(step -> step.toolName() != null && step.toolName().startsWith("tool:"))
                .count();
        int failedSteps = (int) steps.stream()
                .filter(step -> AgentStepStatus.FAILED.name().equalsIgnoreCase(step.status()))
                .count();
        long totalLatencyMs = steps.stream()
                .map(AgentStepView::latencyMs)
                .filter(latency -> latency != null)
                .mapToLong(Long::longValue)
                .sum();
        return new AgentTraceSummaryView(
                runtimes.size(),
                failedRuntimes,
                steps.size(),
                modelTurns,
                toolCalls,
                failedSteps,
                totalLatencyMs
        );
    }

    private List<AgentTraceEventView> buildTraceTimeline(List<RuntimeSlotView> runtimes, List<AgentStepView> steps) {
        List<AgentTraceEventView> events = new ArrayList<>();
        for (RuntimeSlotView runtime : runtimes) {
            addRuntimeEvent(events, "RUNTIME_CREATED", "Runtime created", runtime, runtime.createdAt());
            addRuntimeEvent(events, "RUNTIME_STARTED", "Executor started", runtime, runtime.startedAt());
            addRuntimeEvent(events, "RUNTIME_HEARTBEAT", "Heartbeat: " + nullToDefault(runtime.stage(), "unknown"),
                    runtime, runtime.heartbeatAt());
            addRuntimeEvent(events, "RUNTIME_FINISHED", "Runtime finished", runtime, runtime.finishedAt());
        }
        steps.stream()
                .map(AgentTraceEventView::step)
                .forEach(events::add);
        return events.stream()
                .sorted(Comparator
                        .comparing(AgentTraceEventView::timestamp,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(event -> event.stepOrder() == null ? Integer.MAX_VALUE : event.stepOrder())
                        .thenComparing(AgentTraceEventView::type))
                .toList();
    }

    private void addRuntimeEvent(List<AgentTraceEventView> events,
                                 String type,
                                 String label,
                                 RuntimeSlotView runtime,
                                 java.time.LocalDateTime timestamp) {
        if (timestamp != null) {
            events.add(AgentTraceEventView.runtime(type, label, runtime, timestamp));
        }
    }

    private String nullToDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String blankToNullText(String value) {
        return isBlank(value) ? "null" : value.trim();
    }

    private void heartbeat(AgentRunContext context) {
        if (context.runtimeId() != null) {
            runtimeTaskService.heartbeat(context.runtimeId(), "agent_loop", 50);
        }
    }

    private AgentLoopObserver compose(AgentLoopObserver a, AgentLoopObserver b) {
        if (a == AgentLoopObserver.NOOP) {
            return b;
        }
        if (b == AgentLoopObserver.NOOP) {
            return a;
        }
        return new AgentLoopObserver() {
            @Override
            public void onModelTurn(int turn, String input, String output, long latencyMs) {
                a.onModelTurn(turn, input, output, latencyMs);
                b.onModelTurn(turn, input, output, latencyMs);
            }

            @Override
            public void onToolResult(int turn, String toolName, Map<String, Object> input, Object output, long latencyMs) {
                a.onToolResult(turn, toolName, input, output, latencyMs);
                b.onToolResult(turn, toolName, input, output, latencyMs);
            }

            @Override
            public void onToolError(int turn, String toolName, Map<String, Object> input, Exception error, long latencyMs) {
                a.onToolError(turn, toolName, input, error, latencyMs);
                b.onToolError(turn, toolName, input, error, latencyMs);
            }
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    private static class AgentRunContext {
        private final String taskId;
        private final String runtimeId;
        private final Hot100AgentRunRequest request;
        private final Long userId;
        private int stepOrder = 0;

        private AgentRunContext(String taskId, String runtimeId, Hot100AgentRunRequest request, Long userId) {
            this.taskId = taskId;
            this.runtimeId = runtimeId;
            this.request = request;
            this.userId = userId;
        }

        private String taskId() {
            return taskId;
        }

        private String runtimeId() {
            return runtimeId;
        }

        private Hot100AgentRunRequest request() {
            return request;
        }

        private Long userId() {
            return userId;
        }

        private int nextStepOrder() {
            return ++stepOrder;
        }
    }
}
