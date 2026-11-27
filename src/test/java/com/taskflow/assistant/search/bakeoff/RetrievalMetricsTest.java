package com.taskflow.assistant.search.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.taskflow.assistant.search.bakeoff.EmbeddingFixture.FixtureQuery;
import com.taskflow.assistant.search.bakeoff.RetrievalMetrics.QueryScore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetrievalMetricsTest {

    private static FixtureQuery query(List<String> relevant, List<String> hardNegatives) {
        return new FixtureQuery("q", "검색어", List.of(), "keyword", relevant, List.of(), hardNegatives, null);
    }

    @Test
    @DisplayName("코사인 유사도가 높은 순으로 줄 세우고 같으면 id 순이다")
    void ranksByCosine() {
        Map<String, float[]> documents = new LinkedHashMap<>();
        documents.put("t3", new float[] {0, 1});
        documents.put("t2", new float[] {1, 0});
        documents.put("t1", new float[] {1, 0});

        assertThat(RetrievalMetrics.rank(new float[] {1, 0}, documents)).containsExactly("t1", "t2", "t3");
    }

    @Test
    @DisplayName("첫 정답 순위로 역순위를 내고 정답 중 상위 k 안에 든 비율을 recall로 본다")
    void scoresRankAndRecall() {
        QueryScore score = RetrievalMetrics.score(
                query(List.of("t2", "t9"), List.of("t1")), List.of("t1", "t2", "t3", "t4", "t5", "t6", "t9"));

        assertThat(score.firstRelevantRank()).isEqualTo(2);
        assertThat(score.reciprocalRank()).isEqualTo(0.5);
        assertThat(score.recallAt5()).isEqualTo(0.5);
        assertThat(score.recallAt10()).isEqualTo(1.0);
        assertThat(score.hardNegativeFirst()).isTrue();
    }

    @Test
    @DisplayName("정답을 못 찾으면 역순위 0이고 오답이 없으면 오답 선두가 아니다")
    void missingRelevant() {
        QueryScore score = RetrievalMetrics.score(query(List.of("t9"), List.of()), List.of("t1", "t2"));

        assertThat(score.reciprocalRank()).isZero();
        assertThat(score.hitAt(10)).isFalse();
        assertThat(score.hardNegativeFirst()).isFalse();
    }

    @Test
    @DisplayName("상위 5개 중 정답 비율을 내고 정답이 5개보다 적으면 정답 수로 나눈다")
    void precisionAt5() {
        List<String> ranking = List.of("t1", "t2", "t3", "t4", "t5", "t6");

        assertThat(RetrievalMetrics.score(query(List.of("t1", "t3", "t6", "t7", "t8", "t9"), List.of()), ranking)
                        .precisionAt5())
                .isEqualTo(0.4);
        assertThat(RetrievalMetrics.score(query(List.of("t2", "t9"), List.of()), ranking)
                        .precisionAt5())
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("앞부분만 자르고 길이 1로 다시 맞춘다")
    void truncatesAndNormalizes() {
        float[] truncated = RetrievalMetrics.truncate(new float[] {3, 4, 100}, 2);

        assertThat(truncated[0]).isCloseTo(0.6f, within(1e-6f));
        assertThat(truncated[1]).isCloseTo(0.8f, within(1e-6f));
    }
}
