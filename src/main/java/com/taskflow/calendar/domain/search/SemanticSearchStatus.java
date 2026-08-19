package com.taskflow.calendar.domain.search;

/**
 * 의미 검색이 실제로 탔는지를 응답에 실어 보내기 위한 상태.
 *
 * <p>요약(WeeklySummaryCacheStatus)이 이미 쓰는 규약을 검색에 이식한 것이다 —
 * 정상 경로를 못 타고 결과를 만들었으면 그 사실을 값으로 남긴다.
 * 이전에는 {@code AtomicBoolean available} 하나에 "설정으로 껐다"와 "고장났다"가
 * 함께 붕괴돼 있었고, 그래서 pgvector가 없어 의미 검색이 죽은 상태가
 * log.warn 한 줄 외에는 아무 데도 드러나지 않았다.
 */
public enum SemanticSearchStatus {

    /** 의미 검색이 정상 동작한다. */
    READY,

    /** 설정으로 껐거나 API 키가 없다. 의도된 상태다. */
    DISABLED,

    /** 쓰려고 했는데 실패했다(확장 부재·차원 불일치·쿼리 오류). 고장이다. */
    UNAVAILABLE
}
