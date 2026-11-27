package com.taskflow.assistant.llm.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskflow.assistant.llm.bakeoff.BakeoffFixtures.RecommendationCase;
import com.taskflow.assistant.llm.bakeoff.BakeoffFixtures.SearchCase;
import com.taskflow.assistant.llm.bakeoff.BakeoffFixtures.SummaryCase;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.planning.task.TaskStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BakeoffFixtureTest {

    private final FixtureLoader loader = new FixtureLoader(new ObjectMapper());

    @Nested
    @DisplayName("추천 픽스처")
    class Recommendation {

        private final List<RecommendationCase> cases = loader.recommendationCases();

        @Test
        @DisplayName("후보는 운영 규칙대로 DONE 없이 8개 이하이고 필수·제외 작업이 모두 후보 안에 있다")
        void candidatesFollowProductionRules() {
            for (RecommendationCase fixture : cases) {
                List<Long> ids = ids(fixture);
                assertThat(ids).as(fixture.id()).hasSizeLessThanOrEqualTo(8);
                assertThat(fixture.candidates())
                        .as(fixture.id())
                        .noneMatch(c -> c.getTask().getStatus() == TaskStatus.DONE);
                assertThat(ids).as(fixture.id()).containsAll(fixture.mustIncludeIds());
                assertThat(ids).as(fixture.id()).containsAll(fixture.mustExcludeIds());
                assertThat(fixture.count()).as(fixture.id()).isBetween(1, 5);
            }
        }

        @Test
        @DisplayName("절반 이상은 필수 작업이 seed 3번째 이후에 있고 seed 1번이 정답이 아닌 케이스가 4개 이상이다")
        void mustIncludeIsNotAlwaysFirst() {
            long late = cases.stream()
                    .filter(c -> c.mustIncludeIds().stream().anyMatch(id -> ids(c).indexOf(id) >= 2))
                    .count();
            long seedOneWrong = cases.stream()
                    .filter(c -> !c.mustIncludeIds().contains(ids(c).get(0)))
                    .count();

            assertThat(late).isGreaterThanOrEqualTo((cases.size() + 1) / 2);
            assertThat(seedOneWrong).isGreaterThanOrEqualTo(4);
        }

        private List<Long> ids(RecommendationCase fixture) {
            return fixture.candidates().stream()
                    .map(TaskSyncSnapshot::getTask)
                    .map(task -> task.getId())
                    .toList();
        }
    }

    @Test
    @DisplayName("검색 픽스처는 기본값이 아닌 정답을 가진 케이스가 절반 이상이다")
    void searchHasEnoughNonDefaultCases() {
        List<SearchCase> cases = loader.searchCases();

        long nonDefault = cases.stream()
                .filter(c -> !c.timeIntent().contains("unspecified")
                        || !c.priorityIntent().contains("none")
                        || !c.mainAction().contains("unknown")
                        || !c.targetType().contains("task")
                        || !c.statusIntents().isEmpty())
                .count();

        assertThat(nonDefault).isGreaterThanOrEqualTo(cases.size() / 2);
    }

    @Test
    @DisplayName("요약 입력은 운영 선택 한도(synced 6, unsynced 4, DONE 1) 안이다")
    void summaryInputsRespectProductionLimits() {
        for (SummaryCase fixture : loader.summaryCases()) {
            assertThat(fixture.synced()).as(fixture.id()).hasSizeLessThanOrEqualTo(6);
            assertThat(fixture.unsynced()).as(fixture.id()).hasSizeLessThanOrEqualTo(4);
            assertThat(doneCount(fixture.synced())).as(fixture.id()).isLessThanOrEqualTo(1);
            assertThat(doneCount(fixture.unsynced())).as(fixture.id()).isLessThanOrEqualTo(1);
        }
    }

    private static long doneCount(List<TaskSyncSnapshot> snapshots) {
        return snapshots.stream()
                .filter(s -> s.getTask().getStatus() == TaskStatus.DONE)
                .count();
    }
}
