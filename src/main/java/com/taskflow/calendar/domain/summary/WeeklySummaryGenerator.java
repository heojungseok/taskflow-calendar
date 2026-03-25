package com.taskflow.calendar.domain.summary;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResult;
import com.taskflow.calendar.domain.task.Task;

import java.time.LocalDate;
import java.util.List;

public interface WeeklySummaryGenerator {

    WeeklySummaryResult generate(Project project,
                                 List<Task> tasks,
                                 LocalDate weekStart,
                                 LocalDate weekEnd,
                                 int totalTaskCount);
}
