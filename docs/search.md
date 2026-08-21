# 자연어 검색 동작 방식

`/projects` 화면에서 프로젝트를 먼저 선택하지 않고 전체 Task를 탐색하는 기능(`POST /api/search/tasks`)의 동작 계약입니다.

## 역할 분리

- LLM은 질의를 구조화된 intent로 해석하는 역할만 담당합니다.
- 검색 정책, fallback, 정렬은 서버가 최종 결정합니다.
- 결과는 `Task 우선 + 관련 프로젝트 보조` 구조로 반환됩니다.

## 질의 타입

| 타입 | 설명 |
|---|---|
| `TOPIC_SEARCH` | 주제가 분명한 검색 |
| `RELATIONAL_SEARCH` | 참여자·장소·행동 조건이 함께 필요한 검색 |
| `BROAD_SEARCH` | 구조화 신호가 약한 넓은 검색 |

`TOPIC_SEARCH`에서는 topic anchor를 먼저 통과한 후보 안에서만 action과 semantic 점수가 순위 보정으로 작동합니다.

## semantic recall

- `pgvector`와 Gemini embedding을 사용합니다.
- 주 엔진이 아니라 보조 recall 층으로만 사용합니다.
- 사용하려면 PostgreSQL에 `pgvector` extension이 활성화되어 있어야 합니다.

## 참여자 조건

specific participant와 generic companion을 분리합니다.

- specific participant 예: `친구`, `가족`
- generic companion 예: `누군가와`, `같이`, `함께`

## fallback

구체 앵커가 없는 broad query는 검색을 강행하지 않고 suggested queries와 함께 fallback될 수 있습니다.
