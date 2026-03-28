package com.taskflow.calendar.domain.recommendation.generator;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.summary.SummaryTaskSnapshot;

import java.time.LocalDate;
import java.util.List;

public interface TaskRecommendationGenerator {

    TaskRecommendationGenerationResult generate(Project project,
                                                List<SummaryTaskSnapshot> candidates,
                                                int recommendationCount,
                                                LocalDate today);
}
