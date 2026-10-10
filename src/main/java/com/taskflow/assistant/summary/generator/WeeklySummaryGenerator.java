package com.taskflow.assistant.summary.generator;

import com.taskflow.assistant.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.planning.project.Project;
import java.time.LocalDate;
import java.util.List;

public interface WeeklySummaryGenerator {

    WeeklySummarySectionsResult generate(
            Project project,
            List<TaskSyncSnapshot> syncedTasks,
            int syncedTotalTaskCount,
            List<TaskSyncSnapshot> unsyncedTasks,
            int unsyncedTotalTaskCount,
            LocalDate weekStart,
            LocalDate weekEnd);
}
