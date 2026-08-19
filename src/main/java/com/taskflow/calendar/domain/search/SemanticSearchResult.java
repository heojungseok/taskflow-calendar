package com.taskflow.calendar.domain.search;

import java.util.Map;

/**
 * 의미 검색 유사도와 그 시도의 결과 상태.
 *
 * <p>상태를 분리해 나중에 묻지 않는 이유는 실패를 아는 지점이 여기뿐이기 때문이다.
 * 임베딩 API가 429를 뱉는 경로는 벡터 스토어를 건드리지 않으므로,
 * 스토어에 물으면 "정상"이라고 답한다.
 */
public record SemanticSearchResult(Map<Long, Double> similarities, SemanticSearchStatus status) {
}
