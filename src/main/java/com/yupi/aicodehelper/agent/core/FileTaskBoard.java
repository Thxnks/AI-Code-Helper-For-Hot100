package com.yupi.aicodehelper.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class FileTaskBoard implements TaskBoard {

    private final ObjectMapper objectMapper;
    private final Path taskDirectory;

    public FileTaskBoard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.taskDirectory = Path.of(".tasks");
        ensureDirectory();
    }

    @Override
    public synchronized TaskRecord create(String subject, String description, String owner) {
        TaskRecord task = new TaskRecord(nextId(), subject, description, TaskStatus.PENDING, List.of(), List.of(), owner);
        save(task);
        return task;
    }

    @Override
    public synchronized Optional<TaskRecord> get(long id) {
        Path path = taskPath(id);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(read(path));
    }

    @Override
    public synchronized List<TaskRecord> list(boolean includeDeleted) {
        ensureDirectory();
        try (var stream = Files.list(taskDirectory)) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith("task_"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(this::read)
                    .filter(task -> includeDeleted || task.getStatus() != TaskStatus.DELETED)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list task files", e);
        }
    }

    @Override
    public synchronized TaskRecord update(TaskRecord taskRecord) {
        save(taskRecord);
        return taskRecord;
    }

    @Override
    public synchronized void removeBlockedBy(long blockedTaskId, long dependencyId) {
        Optional<TaskRecord> blockedOptional = get(blockedTaskId);
        if (blockedOptional.isEmpty()) {
            return;
        }
        TaskRecord blocked = blockedOptional.get();
        blocked.getBlockedBy().remove(dependencyId);
        save(blocked);
    }

    @Override
    public synchronized List<TaskRecord> listByGroup(String groupId, boolean includeDeleted) {
        ensureDirectory();
        try (var stream = Files.list(taskDirectory)) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith("task_"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(this::read)
                    .filter(task -> includeDeleted || task.getStatus() != TaskStatus.DELETED)
                    .filter(task -> groupId == null ? task.getGroupId() == null
                            : groupId.equals(task.getGroupId()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list task files", e);
        }
    }

    private long nextId() {
        return list(true).stream()
                .mapToLong(TaskRecord::getId)
                .max()
                .orElse(0L) + 1L;
    }

    private void save(TaskRecord taskRecord) {
        ensureDirectory();
        try {
            objectMapper.writeValue(taskPath(taskRecord.getId()).toFile(), taskRecord);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save task " + taskRecord.getId(), e);
        }
    }

    private TaskRecord read(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), TaskRecord.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read task file: " + path, e);
        }
    }

    private Path taskPath(long id) {
        return taskDirectory.resolve("task_" + id + ".json");
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(taskDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create task directory", e);
        }
    }
}
