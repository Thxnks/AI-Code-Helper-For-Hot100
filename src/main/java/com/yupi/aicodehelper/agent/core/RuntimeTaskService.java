package com.yupi.aicodehelper.agent.core;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Service
public class RuntimeTaskService {

    private final Executor executor;
    private final ConcurrentMap<String, RuntimeSlotState> slotsByRuntimeId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<String>> runtimeIdsByTaskId = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<BackgroundNotification> notificationQueue = new ConcurrentLinkedQueue<>();

    public RuntimeTaskService(@Qualifier("hot100TaskExecutor") Executor executor) {
        this.executor = executor;
    }

    public RuntimeSlotState submit(String taskId, String owner, Consumer<RuntimeSlotState> work) {
        String runtimeId = "rt_" + UUID.randomUUID().toString().replace("-", "");
        int attempt = nextAttempt(taskId);
        RuntimeSlotState state = new RuntimeSlotState(runtimeId, taskId, owner, attempt);
        slotsByRuntimeId.put(runtimeId, state);
        runtimeIdsByTaskId.compute(taskId, (ignored, existing) -> {
            List<String> next = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            next.add(runtimeId);
            return next;
        });

        CompletableFuture.runAsync(() -> {
            state.markRunning(Thread.currentThread().getName());
            try {
                work.accept(state);
                state.markSuccess();
            } catch (Exception e) {
                state.markFailed(truncate(e.getMessage(), 1000));
            }
        }, executor);

        return state;
    }

    public Optional<RuntimeSlotState> get(String runtimeId) {
        return Optional.ofNullable(slotsByRuntimeId.get(runtimeId));
    }

    public Optional<RuntimeSlotState> getLatestByTaskId(String taskId) {
        List<String> runtimeIds = runtimeIdsByTaskId.get(taskId);
        if (runtimeIds == null || runtimeIds.isEmpty()) {
            return Optional.empty();
        }
        return get(runtimeIds.get(runtimeIds.size() - 1));
    }

    public List<RuntimeSlotState> listByTaskId(String taskId) {
        List<String> runtimeIds = runtimeIdsByTaskId.get(taskId);
        if (runtimeIds == null || runtimeIds.isEmpty()) {
            return List.of();
        }
        return runtimeIds.stream()
                .map(slotsByRuntimeId::get)
                .filter(slot -> slot != null)
                .sorted(Comparator.comparingInt(RuntimeSlotState::getAttempt))
                .toList();
    }

    public void heartbeat(String runtimeId, String stage, int progress) {
        get(runtimeId).ifPresent(slot -> slot.heartbeat(stage, progress));
    }

    public List<RuntimeSlotState> list() {
        return slotsByRuntimeId.values().stream()
                .sorted(Comparator.comparing(RuntimeSlotState::getCreatedAt).reversed())
                .toList();
    }

    public void pushNotification(String runtimeId, String status, String preview) {
        notificationQueue.add(new BackgroundNotification(runtimeId, status, preview));
    }

    public List<BackgroundNotification> drainNotifications() {
        List<BackgroundNotification> list = new ArrayList<>();
        BackgroundNotification n;
        while ((n = notificationQueue.poll()) != null) {
            list.add(n);
        }
        return list;
    }

    public RuntimeSlotState runBackgroundTask(String description, Supplier<String> work) {
        String taskId = "bg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String runtimeId = "rt_" + UUID.randomUUID().toString().replace("-", "");
        int attempt = nextAttempt(taskId);
        RuntimeSlotState state = new RuntimeSlotState(runtimeId, taskId, "background-agent", attempt);
        slotsByRuntimeId.put(runtimeId, state);
        runtimeIdsByTaskId.compute(taskId, (ignored, existing) -> {
            List<String> next = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            next.add(runtimeId);
            return next;
        });

        CompletableFuture.runAsync(() -> {
            state.markRunning(Thread.currentThread().getName());
            try {
                String result = work.get();
                state.setOutput(result);
                state.markSuccess();
                notificationQueue.add(new BackgroundNotification(
                        runtimeId, "completed", truncate(result, 500)));
            } catch (Exception e) {
                String errorText = truncate(e.getMessage(), 1000);
                state.setOutput(errorText);
                state.markFailed(errorText);
                notificationQueue.add(new BackgroundNotification(
                        runtimeId, "failed", truncate(errorText, 500)));
            }
        }, executor);

        return state;
    }

    private int nextAttempt(String taskId) {
        List<String> runtimeIds = runtimeIdsByTaskId.get(taskId);
        return runtimeIds == null ? 1 : runtimeIds.size() + 1;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
