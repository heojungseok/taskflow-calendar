# 데모 사용자 정리 실패

- 식별 패턴: `ERROR c.taskflow.demo.DemoCleanupScheduler : Demo cleanup failed. errorType=`
- 로거: `com.taskflow.demo.DemoCleanupScheduler`
- 관련 알림: cleanup failure, cleanup overdue
- 확정: 대기

## 증상
만료된 데모 사용자(가입 24시간 경과)를 지우는 스케줄러가 사용자별 정리에서 예외를 낸다. `demo_cleanup_failures_total`이 늘고, 실패가 이어지면 `demo_oldest_expired_age_seconds`가 커져 정리 지연 알림도 함께 울린다.

## 원인
사용자 하나를 정리하는 트랜잭션이 실패했다. 로그에는 예외 클래스 이름(`errorType`)만 남는다. 흔한 원인은 DB 연결 실패·락 대기 초과, 정리 대상 데이터의 제약 위반이다.

## 확인할 것
- 같은 시각 postgres 지표(연결 수, 락)와 backend의 다른 ERROR 줄
- `errorType`이 `DataAccess` 계열이면 DB 쪽, 그 밖이면 정리 로직 쪽
- 정리 지연 알림이 같이 울렸는지(실패가 한 번인지 계속인지)

## 조치
DB 문제가 풀리면 다음 정리 주기에 자동으로 다시 돈다. 같은 사용자에서 계속 실패하면 그 사용자의 남은 데이터(Task, Outbox)를 확인한다. backend 재시작은 도움이 되지 않는다.
