package com.taskflow.assistant.summary.generator;

import com.taskflow.assistant.summary.SummaryTaskSnapshot;
import com.taskflow.assistant.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.planning.project.Project;
import java.time.LocalDate;
import java.util.List;

public interface WeeklySummaryGenerator {

    WeeklySummarySectionsResult generate(
            Project project,
            List<SummaryTaskSnapshot> syncedTasks,
            int syncedTotalTaskCount,
            List<SummaryTaskSnapshot> unsyncedTasks,
            int unsyncedTotalTaskCount,
            LocalDate weekStart,
            LocalDate weekEnd);
}
