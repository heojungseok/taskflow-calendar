package com.taskflow.assistant.llm.bakeoff;

import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.planning.project.Project;
import java.time.LocalDate;
import java.util.List;

final class BakeoffFixtures {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    private BakeoffFixtures() {}

    record SearchCase(
            String id,
            String query,
            String targetType,
            String timeIntent,
            String priorityIntent,
            String mainAction,
            List<String> statusIntents,
            List<String> expectedTopicKeywords) {}

    record RecommendationCase(
            String id,
            Project project,
            int count,
            List<Long> mustIncludeIds,
            List<Long> mustExcludeIds,
            List<TaskSyncSnapshot> candidates) {}

    record SummaryCase(
            String id,
            Project project,
            LocalDate weekStart,
            LocalDate weekEnd,
            List<TaskSyncSnapshot> synced,
            List<TaskSyncSnapshot> unsynced) {}
}
