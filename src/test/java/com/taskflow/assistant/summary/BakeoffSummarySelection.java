package com.taskflow.assistant.summary;

import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import java.time.LocalDate;
import java.util.List;

public final class BakeoffSummarySelection {

    private static final SummaryTaskSelector SELECTOR = new SummaryTaskSelector();

    private BakeoffSummarySelection() {}

    public static List<TaskSyncSnapshot> selectIncluded(List<TaskSyncSnapshot> tasks, boolean synced, LocalDate today) {
        List<TaskSyncSnapshot> prioritized =
                tasks.stream().sorted(SELECTOR.taskPriorityComparator(today)).toList();
        return SELECTOR.selectIncludedTasks(prioritized, synced ? SummaryBucket.SYNCED : SummaryBucket.UNSYNCED);
    }
}
