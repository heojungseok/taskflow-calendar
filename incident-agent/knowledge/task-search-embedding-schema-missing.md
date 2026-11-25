# 검색 임베딩 테이블 없음

- 식별 패턴: `ERROR c.t.a.s.TaskSearchEmbeddingStore : Task search embedding schema missing. Run the deployment migration first.`
- 로거: `com.taskflow.assistant.search.TaskSearchEmbeddingStore`
- 관련 알림: backend error logs
- 확정: 대기

## 증상
backend 기동 직후 한 번 찍히고, 자연어 검색(의미 검색)이 꺼진 채로 동작한다. 다른 기능은 정상이다.

## 원인
운영에서는 스키마 자동 생성이 꺼져 있는데(`schema-management-enabled=false`) `task_search_embeddings` 테이블이 없다. migration을 적용하지 않은 배포이거나 DB를 백업에서 되살리며 테이블이 빠진 경우다.

## 확인할 것
- 직전 배포가 migration 범위였는지, 승인·적용이 끝났는지
- DB에 `task_search_embeddings` 테이블이 있는지

## 조치
migration을 적용한 뒤 backend를 재기동한다. 재기동만으로는 고쳐지지 않는다(테이블이 없으면 다시 꺼진다).
