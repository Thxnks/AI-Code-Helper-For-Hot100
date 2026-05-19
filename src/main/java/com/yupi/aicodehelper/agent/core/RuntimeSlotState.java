package com.yupi.aicodehelper.agent.core;

import java.time.LocalDateTime;

public class RuntimeSlotState {

    private final String runtimeId;
    private final String taskId;
    private final String owner;
    private final int attempt;
    private volatile String executorId;
    private volatile RuntimeSlotStatus status;
    private volatile String stage;
    private volatile int progress;
    private volatile String errorMessage;
    private volatile String output;
    private final LocalDateTime createdAt;
    private volatile LocalDateTime heartbeatAt;
    private volatile LocalDateTime startedAt;
    private volatile LocalDateTime finishedAt;
    private volatile LocalDateTime updatedAt;

    RuntimeSlotState(String runtimeId, String taskId, String owner, int attempt) {
        LocalDateTime now = LocalDateTime.now();
        this.runtimeId = runtimeId;
        this.taskId = taskId;
        this.owner = owner;
        this.attempt = attempt;
        this.status = RuntimeSlotStatus.PENDING;
        this.stage = "queued";
        this.progress = 0;
        this.createdAt = now;
        this.heartbeatAt = now;
        this.updatedAt = now;
    }

    synchronized void markRunning(String executorId) {
        LocalDateTime now = LocalDateTime.now();
        this.executorId = executorId;
        this.status = RuntimeSlotStatus.RUNNING;
        this.stage = "running";
        this.progress = Math.max(progress, 10);
        this.heartbeatAt = now;
        this.startedAt = now;
        this.updatedAt = now;
    }

    synchronized void heartbeat(String stage, int progress) {
        LocalDateTime now = LocalDateTime.now();
        this.stage = stage == null || stage.isBlank() ? this.stage : stage;
        this.progress = Math.max(0, Math.min(progress, 100));
        this.heartbeatAt = now;
        this.updatedAt = now;
    }

    synchronized void markSuccess() {
        LocalDateTime now = LocalDateTime.now();
        this.status = RuntimeSlotStatus.SUCCESS;
        this.stage = "finished";
        this.progress = 100;
        this.errorMessage = null;
        this.heartbeatAt = now;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    synchronized void markFailed(String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        this.status = RuntimeSlotStatus.FAILED;
        this.stage = "failed";
        this.errorMessage = errorMessage;
        this.heartbeatAt = now;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    public String getRuntimeId() {
        return runtimeId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getOwner() {
        return owner;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getExecutorId() {
        return executorId;
    }

    public RuntimeSlotStatus getStatus() {
        return status;
    }

    public String getStage() {
        return stage;
    }

    public int getProgress() {
        return progress;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getOutput() {
        return output;
    }

    synchronized void setOutput(String output) {
        this.output = output;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getHeartbeatAt() {
        return heartbeatAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
