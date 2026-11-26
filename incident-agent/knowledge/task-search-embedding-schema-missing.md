# 검색 임베딩 테이블을 확인하지 못함

- 식별 패턴: `ERROR c.t.a.s.TaskSearchEmbeddingStore : Task search embedding schema missing. Run the deployment migration first.`
- 로거: `com.taskflow.assistant.search.TaskSearchEmbeddingStore`
- 관련 알림: backend error logs
- 방문자 유발: 불가
- 확정: 2026-11-26 허정석

## 증상
backend 기동 직후 한 번 찍히고, 자연어 검색(의미 검색)이 꺼진 채로 동작한다. 다른 기능은 정상이다.

## 원인
기동 때 임베딩 차원을 읽지 못했다. 차원 조회가 DB 오류로 실패해도 "테이블 없음"과 같은 결과가 되어 이 줄이 남는다. 그래서 대부분은 기동 순간의 DB 연결 문제다. 테이블은 baseline migration에 있고 backend는 migrator가 끝나야 뜨므로, 테이블이 실제로 없는 경우는 드물다(DB를 백업에서 되살리며 빠진 경우 등).

## 확인할 것
- 바로 앞에 `Task search embedding dimension lookup failed` WARN이 있는지(있으면 DB 연결 문제)
- 그 시각 postgres 상태
- DB에 `task_search_embeddings` 테이블이 있는지

## 조치
DB 연결 문제였다면 DB가 회복된 뒤 backend를 재시작하면 검색이 다시 켜진다. 테이블이 정말 없으면 migration 적용이 먼저다(재시작만으로는 다시 꺼진다).
