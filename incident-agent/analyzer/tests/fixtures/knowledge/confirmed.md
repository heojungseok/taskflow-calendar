# 데모 정리 작업 실패

- 식별 패턴: `ERROR c.taskflow.demo.DemoCleanupScheduler : Demo cleanup failed. errorType=`
- 로거: `com.taskflow.demo.DemoCleanupScheduler`
- 관련 알림: cleanup failure
- 방문자 유발: 불가
- 확정: 2026-11-26 정석님

## 증상
정리 작업이 실패한다.

## 원인
DB 연결 문제.

## 확인할 것
postgres 상태.

## 조치
DB가 돌아오면 다음 주기에 다시 돈다.
