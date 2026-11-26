# Outbox 처리 중 Google 토큰 갱신 실패

- 식별 패턴: `ERROR c.t.c.worker.CalendarOutboxWorker : [Worker] Token refresh failed for Outbox #:`
- 로거: `com.taskflow.calendarsync.worker.CalendarOutboxWorker`
- 관련 알림: backend error logs
- 방문자 유발: 가능
- 확정: 대기

## 증상
Google Calendar 동기화가 401을 받아 토큰 갱신을 시도했고, 갱신이 실패해 그 Outbox가 영구 FAILED가 된다. 해당 일정 변경은 캘린더에 반영되지 않는다. FAILED는 처리 대기에서 빠지므로 worker backlog 알림은 울리지 않는다.

## 원인
갱신 중의 모든 예외가 같은 줄로 남는다. `#:` 뒤 꼬리 문구로 구분한다.
- `Refresh token 만료 또는 폐기.`: 사용자가 Google에서 연결을 해제했거나 refresh token이 폐기됐다(방문자가 일부러 만들 수 있음). 이후 토큰이 지워져 같은 사용자의 다음 Outbox는 이 줄 없이 SKIPPED가 된다
- `Token refresh 일시적 실패`: Google 쪽 네트워크·5xx 장애
- `No access token found`: 사용자 토큰이 이미 없음

## 확인할 것
- 꼬리 문구가 무엇인지
- 일시적 실패가 여러 사용자에게서 동시에 나는지(Google 쪽 장애)

## 조치
이미 FAILED가 된 Outbox는 자동 재시도나 재동기화 경로가 없어 저절로 살아나지 않는다. 사용자가 다시 연결한 뒤 Task를 다시 고쳐야 반영된다. Google 일시 장애로 여러 건이 FAILED가 됐다면 그 건들은 수동으로 다시 반영해야 한다. backend 재시작은 도움이 되지 않는다.
