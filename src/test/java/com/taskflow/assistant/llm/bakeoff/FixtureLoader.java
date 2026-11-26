package com.taskflow.assistant.llm.bakeoff;

import com.taskflow.assistant.llm.bakeoff.BakeoffFixtures.RecommendationCase;
import com.taskflow.assistant.llm.bakeoff.BakeoffFixtures.SearchCase;
import com.taskflow.assistant.llm.bakeoff.BakeoffFixtures.SummaryCase;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.planning.project.Project;
import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskStatus;
import com.taskflow.planning.task.TaskSyncState;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class FixtureLoader {

    private static final String BASE = "/llm-bakeoff/";

    private final ObjectMapper objectMapper;

    FixtureLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<SearchCase> searchCases() {
        List<SearchCase> cases = new ArrayList<>();
        for (JsonNode node : read("search.json")) {
            cases.add(new SearchCase(
                    text(node, "id"),
                    text(node, "query"),
                    text(node, "targetType"),
                    text(node, "timeIntent"),
                    text(node, "priorityIntent"),
                    text(node, "mainAction"),
                    strings(node.path("statusIntents")),
                    strings(node.path("expectedTopicKeywords"))));
        }
        return cases;
    }

    List<RecommendationCase> recommendationCases() {
        List<RecommendationCase> cases = new ArrayList<>();
        for (JsonNode node : read("recommendation.json")) {
            Project project = project(text(node, "project"));
            List<TaskSyncSnapshot> candidates = new ArrayList<>();
            for (JsonNode taskNode : node.path("tasks")) {
                candidates.add(snapshot(
                        project,
                        taskNode,
                        TaskSyncState.SYNCED,
                        taskNode.path("id").asLong(0)));
            }
            cases.add(new RecommendationCase(
                    text(node, "id"),
                    project,
                    node.path("count").asInt(2),
                    longs(node.path("mustIncludeIds")),
                    longs(node.path("mustExcludeIds")),
                    candidates));
        }
        return cases;
    }

    List<SummaryCase> summaryCases() {
        List<SummaryCase> cases = new ArrayList<>();
        for (JsonNode node : read("summary.json")) {
            Project project = project(text(node, "project"));
            long[] nextId = {1};
            cases.add(new SummaryCase(
                    text(node, "id"),
                    project,
                    LocalDate.parse(text(node, "weekStart")),
                    LocalDate.parse(text(node, "weekEnd")),
                    snapshots(project, node.path("synced"), TaskSyncState.SYNCED, nextId),
                    snapshots(project, node.path("unsynced"), TaskSyncState.SYNC_DISABLED, nextId)));
        }
        return cases;
    }

    private List<TaskSyncSnapshot> snapshots(Project project, JsonNode tasks, TaskSyncState state, long[] nextId) {
        List<TaskSyncSnapshot> snapshots = new ArrayList<>();
        for (JsonNode taskNode : tasks) {
            snapshots.add(snapshot(project, taskNode, state, nextId[0]++));
        }
        return snapshots;
    }

    private TaskSyncSnapshot snapshot(Project project, JsonNode node, TaskSyncState defaultState, long id) {
        Task task = Task.createTask(
                project,
                text(node, "title"),
                text(node, "description"),
                null,
                null,
                LocalDateTime.parse(text(node, "dueAt")),
                false);
        ReflectionTestUtils.setField(task, "id", id);
        task.changeStatus(TaskStatus.valueOf(text(node, "status", TaskStatus.REQUESTED.name())));
        String state = text(node, "syncState", defaultState.name());
        return TaskSyncSnapshot.of(task, TaskSyncState.valueOf(state), null, null, null);
    }

    private Project project(String name) {
        Project project = Project.of(name, 1L);
        ReflectionTestUtils.setField(project, "id", 1L);
        return project;
    }

    private JsonNode read(String file) {
        try (InputStream in = FixtureLoader.class.getResourceAsStream(BASE + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + file);
            }
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read fixture " + file, e);
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asString("");
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        return node.path(field).asString(defaultValue);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asString(""));
        }
        return values;
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asLong(0));
        }
        return values;
    }
}
