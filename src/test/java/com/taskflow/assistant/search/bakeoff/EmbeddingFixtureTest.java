package com.taskflow.assistant.search.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskflow.assistant.search.bakeoff.EmbeddingFixture.FixtureQuery;
import com.taskflow.assistant.search.bakeoff.EmbeddingFixture.FixtureTask;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EmbeddingFixtureTest {

    private static final List<String> FILTER_WORDS =
            List.of("오늘", "내일", "어제", "이번 주", "다음 주", "지난주", "이번 달", "마감", "완료", "진행 중", "막힌", "막혀", "급한", "꼭");

    private final EmbeddingFixture fixture = EmbeddingFixture.load(new ObjectMapper());
    private final Set<String> taskIds =
            fixture.tasks().stream().map(FixtureTask::id).collect(Collectors.toSet());

    @Test
    @DisplayName("Task는 200~300개이고 id가 겹치지 않으며 제목이 비어 있지 않다")
    void corpusSize() {
        assertThat(fixture.tasks()).hasSizeBetween(200, 300);
        assertThat(taskIds).hasSize(fixture.tasks().size());
        assertThat(fixture.tasks()).allSatisfy(task -> assertThat(task.title()).isNotBlank());
    }

    @Test
    @DisplayName("독립 검색어는 100~120개, 변형 묶음은 15개 안팎이고 묶음마다 3~5개다")
    void querySplit() {
        List<FixtureQuery> independent = independent();
        Map<String, Long> families = fixture.queries().stream()
                .filter(FixtureQuery::inFamily)
                .collect(Collectors.groupingBy(FixtureQuery::family, Collectors.counting()));

        assertThat(independent).hasSizeBetween(100, 120);
        assertThat(families).hasSizeBetween(13, 17);
        assertThat(families.values()).allSatisfy(count -> assertThat(count).isBetween(3L, 5L));
        assertThat(fixture.queries().stream().map(FixtureQuery::id).collect(Collectors.toSet()))
                .hasSize(fixture.queries().size());
    }

    @Test
    @DisplayName("독립 검색어는 유형마다 10개 이상이고 정해진 유형만 쓴다")
    void categoriesCovered() {
        Map<String, Long> byCategory =
                independent().stream().collect(Collectors.groupingBy(FixtureQuery::category, Collectors.counting()));

        assertThat(EmbeddingFixture.CATEGORIES).containsAll(byCategory.keySet());
        for (String category : EmbeddingFixture.CATEGORIES) {
            if (!category.equals("paraphrase")) {
                assertThat(byCategory.getOrDefault(category, 0L)).as(category).isGreaterThanOrEqualTo(10);
            }
        }
    }

    @Test
    @DisplayName("정답은 1~3개이고 모든 참조가 있는 Task이며 정답·부분 정답·오답이 겹치지 않는다")
    void labelsReferToCorpus() {
        for (FixtureQuery query : fixture.queries()) {
            assertThat(query.relevant()).as(query.id()).hasSizeBetween(1, 3);
            List<String> all = new ArrayList<>(query.relevant());
            all.addAll(nullToEmpty(query.partial()));
            all.addAll(nullToEmpty(query.hardNegatives()));
            assertThat(taskIds).as(query.id()).containsAll(all);
            assertThat(new HashSet<>(all)).as(query.id()).hasSize(all.size());
        }
    }

    @Test
    @DisplayName("헷갈리는 오답으로 쓰인 Task가 전체의 30% 이상이다")
    void hardNegativesCoverCorpus() {
        Set<String> hardNegatives = fixture.queries().stream()
                .flatMap(query -> nullToEmpty(query.hardNegatives()).stream())
                .collect(Collectors.toSet());

        assertThat(hardNegatives.size()).isGreaterThanOrEqualTo(fixture.tasks().size() * 3 / 10);
    }

    @Test
    @DisplayName("검색어에 날짜·상태·우선순위 필터 표현이 없다 - 그건 의도 파서 몫이다")
    void noFilterWords() {
        assertThat(fixture.queries())
                .allSatisfy(query -> assertThat(FILTER_WORDS)
                        .as(query.id() + " " + query.query())
                        .noneMatch(word -> query.query().contains(word)));
    }

    @Test
    @DisplayName("같은 묶음의 검색어는 정답이 같다")
    void familiesShareAnswers() {
        Map<String, List<FixtureQuery>> families = fixture.queries().stream()
                .filter(FixtureQuery::inFamily)
                .collect(Collectors.groupingBy(FixtureQuery::family));

        families.forEach((family, members) -> assertThat(members.stream()
                        .map(member -> Set.copyOf(member.relevant()))
                        .collect(Collectors.toSet()))
                .as(family)
                .hasSize(1));
    }

    private List<FixtureQuery> independent() {
        return fixture.queries().stream().filter(query -> !query.inFamily()).toList();
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
