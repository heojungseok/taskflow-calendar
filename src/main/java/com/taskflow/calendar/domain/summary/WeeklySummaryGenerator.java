package com.taskflow.calendar.domain.summary;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.summary.dto.WeeklySummarySectionsResult;

import java.time.LocalDate;
import java.util.List;

public interface WeeklySummaryGenerator {

    WeeklySummarySectionsResult generate(Project project,
                                 List<SummaryTaskSnapshot> syncedTasks,
                                 int syncedTotalTaskCount,
                                 List<SummaryTaskSnapshot> unsyncedTasks,
                                 int unsyncedTotalTaskCount,
                                 LocalDate weekStart,
                                 LocalDate weekEnd);
}
