# Outbox 처리 중 Google 토큰 갱신 실패

- 식별 패턴: `ERROR c.t.c.worker.CalendarOutboxWorker : [Worker] Token refresh failed for Outbox #:`
- 로거: `com.taskflow.calendarsync.worker.CalendarOutboxWorker`
- 관련 알림: worker backlog, backend error logs
- 확정: 대기

## 증상
Google Calendar 동기화 Outbox가 401을 받아 토큰 갱신을 시도했지만 실패해 FAILED가 된다. 해당 사용자의 일정이 캘린더에 반영되지 않는다.

## 원인
사용자가 Google 계정에서 TaskFlow 연결을 해제했거나 refresh token이 만료·폐기됐다. 드물게 Google OAuth 일시 장애다.

## 확인할 것
- 같은 사용자에게서만 반복되는지(사용자 쪽 연결 문제), 여러 사용자에게서 동시에 나는지(Google 쪽 장애)
- 같은 시각 `GoogleOAuthService` 토큰 교환 실패 줄

## 조치
한 사용자만이면 운영 조치가 필요 없다. 사용자가 다시 연결하면 풀린다. 여러 사용자에게서 동시에 나면 Google 상태를 확인하고 기다린다. backend 재시작은 도움이 되지 않는다.
