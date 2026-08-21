# 주간 요약 동작 방식

프로젝트별 주간 업무 요약(`POST /api/projects/{projectId}/weekly-summary`)의 동작 계약입니다.

## 요약 구성

- 요약은 `동기화된 일정` / `미동기화 일정` 2개 섹션으로 나뉩니다.
- 분류는 단순 `calendarEventId` 존재 여부가 아니라 최신 Outbox 상태까지 반영해 판정합니다.
- 모든 Task를 그대로 보내지 않고, 우선순위가 높은 대표 Task subset만 선별해 보냅니다.
- 프롬프트에 `includedTaskCount`와 `totalTaskCount`를 함께 보내서, 대표 subset 기반 요약이라는 점을 모델에 명시합니다.
- 요약 문장은 Task 제목보다 `description`의 목적·제약·리스크·산출물 정보를 우선 반영하되, description은 짧은 `descBrief`와 신호값으로 압축합니다.
- `startAt`은 실제 캘린더 시작 시점, `dueAt`은 마감/종료 시점으로 분리해 사용합니다.

## 캐시와 fallback

- 성공 응답의 `cacheStatus`는 `LIVE`, `CACHE_HIT`, `STALE_FALLBACK` 중 하나입니다.
- 캐시 키는 두 종류입니다.
  - `exact`: 같은 입력 fingerprint일 때만 재사용
  - `latest`: 429 등 fallback 대상 실패 시 마지막 성공 요약 반환
- Gemini 429는 `LLM_RATE_LIMITED_TEMPORARY`, `LLM_QUOTA_EXHAUSTED`, `LLM_429_UNKNOWN`으로 분류합니다.
- Gemini 무료 티어 quota를 초과하거나 일시적 rate limit이 발생하면 live 생성 대신 저장된 요약으로 fallback될 수 있습니다.
- `forceLive=true`는 테스트/운영용 제어값이며, `WEEKLY_SUMMARY_FORCE_LIVE_ENABLED=true`일 때만 허용됩니다.

## 관련 설정

캐시는 기본 비활성화입니다. 사용하려면 `WEEKLY_SUMMARY_CACHE_ENABLED=true`와 `REDIS_URL`을 함께 설정해야 합니다.

Redis read/write 상태는 `GET /api/projects/weekly-summary/cache-health`로 확인합니다.
