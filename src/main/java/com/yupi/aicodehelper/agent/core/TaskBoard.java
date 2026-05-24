package com.yupi.aicodehelper.agent.core;

import java.util.List;
import java.util.Optional;

public interface TaskBoard {

    TaskRecord create(String subject, String description, String owner);

    Optional<TaskRecord> get(long id);

    List<TaskRecord> list(boolean includeDeleted);

    TaskRecord update(TaskRecord taskRecord);

    void removeBlockedBy(long blockedTaskId, long dependencyId);

    List<TaskRecord> listByGroup(String groupId, boolean includeDeleted);
}
