package com.taskflow.planning.task;

import java.util.List;
import java.util.Map;

/** Task 목록에 붙일 캘린더 동기화 상태를 읽는 포트. 구현은 calendarsync가 맡는다. */
public interface TaskSyncStateReader {

    /** taskId → 동기화 상태. 목록 크기와 무관하게 한 번에 조회해야 한다. */
    Map<Long, TaskSyncState> readAll(List<Task> tasks);
}
