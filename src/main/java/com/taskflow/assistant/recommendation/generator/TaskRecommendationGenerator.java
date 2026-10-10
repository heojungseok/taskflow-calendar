package com.taskflow.assistant.recommendation.generator;

import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.planning.project.Project;
import java.time.LocalDate;
import java.util.List;

public interface TaskRecommendationGenerator {

    TaskRecommendationGenerationResult generate(
            Project project, List<TaskSyncSnapshot> candidates, int recommendationCount, LocalDate today);
}
