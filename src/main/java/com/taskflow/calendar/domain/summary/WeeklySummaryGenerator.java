package com.taskflow.calendar.domain.summary;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResult;

import java.time.LocalDate;
import java.util.List;

public interface WeeklySummaryGenerator {

    WeeklySummaryResult generate(Project project,
                                 List<SummaryTaskSnapshot> tasks,
                                 LocalDate weekStart,
                                 LocalDate weekEnd,
                                 int totalTaskCount,
                                 SummaryBucket bucket);
}
