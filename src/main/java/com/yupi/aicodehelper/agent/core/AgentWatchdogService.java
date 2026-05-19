package com.yupi.aicodehelper.agent.core;

import com.yupi.aicodehelper.repository.AgentTaskRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AgentWatchdogService {

    private static final long HEARTBEAT_TIMEOUT_SECONDS = 300;

    private final RuntimeTaskService runtimeTaskService;
    private final AgentTaskRepository agentTaskRepository;

    public AgentWatchdogService(RuntimeTaskService runtimeTaskService,
                                AgentTaskRepository agentTaskRepository) {
        this.runtimeTaskService = runtimeTaskService;
        this.agentTaskRepository = agentTaskRepository;
    }

    @Scheduled(fixedDelay = 30000)
    public void inspectStaleRuntimes() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(HEARTBEAT_TIMEOUT_SECONDS);
        for (RuntimeSlotState slot : runtimeTaskService.list()) {
            if (slot.getStatus() != RuntimeSlotStatus.RUNNING) {
                continue;
            }
            if (slot.getHeartbeatAt() == null || !slot.getHeartbeatAt().isBefore(deadline)) {
                continue;
            }
            slot.markFailed("Heartbeat timeout: no heartbeat for over " + HEARTBEAT_TIMEOUT_SECONDS + " seconds");
            runtimeTaskService.pushNotification(slot.getRuntimeId(), "timeout",
                    "task timed out after " + HEARTBEAT_TIMEOUT_SECONDS + "s");
            agentTaskRepository.findByTaskId(slot.getTaskId()).ifPresent(task -> {
                if ("RUNNING".equals(task.getStatus())) {
                    task.setStatus("FAILED");
                    task.setErrorMessage("Runtime timeout: heartbeat lost");
                    agentTaskRepository.save(task);
                }
            });
        }
    }
}
