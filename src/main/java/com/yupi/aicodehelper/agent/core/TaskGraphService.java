package com.yupi.aicodehelper.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TaskGraphService {

    private final TaskBoard taskBoard;

    public TaskGraphService(TaskBoard taskBoard) {
        this.taskBoard = taskBoard;
    }

    public boolean isFileSystemBacked() {
        return taskBoard instanceof FileTaskBoard;
    }

    public TaskRecord create(String subject, String description, String owner, List<Long> blockedBy) {
        return create(subject, description, owner, blockedBy, null);
    }

    public TaskRecord create(String subject, String description, String owner, List<Long> blockedBy, String groupId) {
        TaskRecord created = taskBoard.create(subject, description, owner);
        if (groupId != null) {
            created.setGroupId(groupId);
            taskBoard.update(created);
        }
        for (Long dependencyId : blockedBy == null ? List.<Long>of() : blockedBy) {
            addDependency(dependencyId, created.getId());
        }
        return taskBoard.get(created.getId()).orElse(created);
    }

    public TaskRecord update(long id,
                             String subject,
                             String description,
                             String owner,
                             TaskStatus status,
                             List<Long> addBlocks,
                             List<Long> removeBlocks,
                             List<Long> blockedBy) {
        TaskRecord task = requireTask(id);
        if (subject != null && !subject.isBlank()) {
            task.setSubject(subject.trim());
        }
        if (description != null) {
            task.setDescription(description.trim());
        }
        if (owner != null) {
            task.setOwner(owner.trim());
        }
        if (blockedBy != null) {
            resetDependencies(task, blockedBy);
        }
        if (addBlocks != null) {
            for (Long blockedId : addBlocks) {
                addDependency(task.getId(), blockedId);
            }
        }
        if (removeBlocks != null) {
            for (Long blockedId : removeBlocks) {
                removeDependency(task.getId(), blockedId);
            }
        }
        if (status != null) {
            task.setStatus(status);
            taskBoard.update(task);
            if (status == TaskStatus.COMPLETED) {
                unlockFollowers(task.getId());
            }
            return requireTask(task.getId());
        }
        return taskBoard.update(task);
    }

    public TaskRecord get(long id) {
        return requireTask(id);
    }

    public List<Map<String, Object>> list(boolean includeDeleted) {
        return list(includeDeleted, null);
    }

    public List<Map<String, Object>> list(boolean includeDeleted, String groupId) {
        return taskBoard.listByGroup(groupId, includeDeleted).stream()
                .map(this::taskToMap)
                .toList();
    }

    private Map<String, Object> taskToMap(TaskRecord task) {
        return Map.of(
                "id", task.getId(),
                "subject", task.getSubject(),
                "description", task.getDescription(),
                "status", task.getStatus(),
                "blockedBy", List.copyOf(task.getBlockedBy()),
                "blocks", List.copyOf(task.getBlocks()),
                "owner", task.getOwner() == null ? "" : task.getOwner(),
                "ready", task.isReady(),
                "groupId", task.getGroupId() == null ? "" : task.getGroupId()
        );
    }

    private void resetDependencies(TaskRecord task, List<Long> nextBlockedBy) {
        List<Long> previous = new ArrayList<>(task.getBlockedBy());
        for (Long oldDependencyId : previous) {
            removeDependency(oldDependencyId, task.getId());
        }
        for (Long newDependencyId : nextBlockedBy) {
            addDependency(newDependencyId, task.getId());
        }
    }

    private void unlockFollowers(long completedTaskId) {
        TaskRecord completed = requireTask(completedTaskId);
        for (Long blockedId : List.copyOf(completed.getBlocks())) {
            taskBoard.removeBlockedBy(blockedId, completedTaskId);
        }
    }

    private void addDependency(long taskId, long blocksId) {
        if (taskId == blocksId) {
            throw new IllegalArgumentException("task cannot depend on itself");
        }
        TaskRecord task = requireTask(taskId);
        TaskRecord blocked = requireTask(blocksId);
        if (!task.getBlocks().contains(blocksId)) {
            task.getBlocks().add(blocksId);
            taskBoard.update(task);
        }
        if (!blocked.getBlockedBy().contains(taskId)) {
            blocked.getBlockedBy().add(taskId);
            taskBoard.update(blocked);
        }
    }

    private void removeDependency(long taskId, long blocksId) {
        TaskRecord task = requireTask(taskId);
        TaskRecord blocked = requireTask(blocksId);
        task.getBlocks().remove(blocksId);
        blocked.getBlockedBy().remove(taskId);
        taskBoard.update(task);
        taskBoard.update(blocked);
    }

    private TaskRecord requireTask(long id) {
        return taskBoard.get(id).orElseThrow(() -> new IllegalArgumentException("task not found: " + id));
    }
}
