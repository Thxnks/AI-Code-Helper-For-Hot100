package com.yupi.aicodehelper.agent.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryTaskBoard implements TaskBoard {

    private final AtomicLong idGenerator = new AtomicLong(1);
    private final Map<Long, TaskRecord> records = new LinkedHashMap<>();

    @Override
    public synchronized TaskRecord create(String subject, String description, String owner) {
        long id = idGenerator.getAndIncrement();
        TaskRecord task = new TaskRecord(id, subject, description, TaskStatus.PENDING, List.of(), List.of(), owner);
        records.put(id, task);
        return copy(task);
    }

    @Override
    public synchronized Optional<TaskRecord> get(long id) {
        TaskRecord task = records.get(id);
        return task == null ? Optional.empty() : Optional.of(copy(task));
    }

    @Override
    public synchronized List<TaskRecord> list(boolean includeDeleted) {
        return records.values().stream()
                .filter(task -> includeDeleted || task.getStatus() != TaskStatus.DELETED)
                .sorted(Comparator.comparingLong(TaskRecord::getId))
                .map(this::copy)
                .toList();
    }

    @Override
    public synchronized TaskRecord update(TaskRecord taskRecord) {
        records.put(taskRecord.getId(), copy(taskRecord));
        return copy(taskRecord);
    }

    @Override
    public synchronized void removeBlockedBy(long blockedTaskId, long dependencyId) {
        TaskRecord blocked = records.get(blockedTaskId);
        if (blocked == null) {
            return;
        }
        blocked.getBlockedBy().remove(dependencyId);
    }

    @Override
    public synchronized List<TaskRecord> listByGroup(String groupId, boolean includeDeleted) {
        return records.values().stream()
                .filter(task -> includeDeleted || task.getStatus() != TaskStatus.DELETED)
                .filter(task -> groupId == null ? task.getGroupId() == null
                        : groupId.equals(task.getGroupId()))
                .sorted(Comparator.comparingLong(TaskRecord::getId))
                .map(this::copy)
                .toList();
    }

    private TaskRecord copy(TaskRecord source) {
        return new TaskRecord(
                source.getId(),
                source.getSubject(),
                source.getDescription(),
                source.getStatus(),
                new ArrayList<>(source.getBlockedBy()),
                new ArrayList<>(source.getBlocks()),
                source.getOwner()
        );
    }
}
