# 데모 사용자 정리 실패

- 식별 패턴: `ERROR c.taskflow.demo.DemoCleanupScheduler : Demo cleanup failed. errorType=`
- 로거: `com.taskflow.demo.DemoCleanupScheduler`
- 관련 알림: cleanup failure, cleanup overdue
- 방문자 유발: 불가
- 확정: 대기

## 증상
만료된 데모 사용자(가입 24시간 경과)를 지우는 스케줄러가 사용자 한 명의 정리에서 예외를 낸다. `demo_cleanup_failures_total`이 늘고, 실패가 이어지면 `demo_oldest_expired_age_seconds`가 커져 정리 지연 알림도 울린다.

## 원인
사용자 하나를 정리하는 트랜잭션이 실패했다. 로그에는 예외 클래스 이름(`errorType`)만 남고 사용자 id는 없다.
- `CannotCreateTransactionException`, `QueryTimeoutException`, `PessimisticLockingFailureException`: DB 연결·락 문제
- `DataIntegrityViolationException`: 정리 대상 데이터의 제약 위반(데이터 쪽 문제)
DB가 완전히 내려가면 이 줄이 아니라 대상 조회 단계의 Spring 스케줄러 ERROR가 먼저 나고 실패 카운터도 늘지 않는다.

## 확인할 것
- `errorType`이 위 어느 쪽인지
- `demo_oldest_expired_age_seconds`가 계속 커지는지(같은 사용자에서 막혔다는 신호)
- 계속 커지면 DB에서 가장 오래된 만료 DEMO 사용자와 그 사용자의 남은 데이터(Task, Outbox)

## 조치
DB 연결·락 문제는 회복되면 다음 정리 주기에 자동으로 다시 돈다. 제약 위반이 반복되면 해당 사용자의 데이터를 직접 확인해 정리한다. backend 재시작은 도움이 되지 않는다.
