package com.taskflow.assistant.recommendation.generator;

import com.taskflow.assistant.summary.SummaryTaskSnapshot;
import com.taskflow.planning.project.Project;
import java.time.LocalDate;
import java.util.List;

public interface TaskRecommendationGenerator {

    TaskRecommendationGenerationResult generate(
            Project project, List<SummaryTaskSnapshot> candidates, int recommendationCount, LocalDate today);
}
