# Google 로그인 토큰 교환 실패

- 식별 패턴: `ERROR c.t.i.oauth.GoogleOAuthService : Google token exchange failed. errorType=`
- 로거: `com.taskflow.identity.oauth.GoogleOAuthService`
- 관련 알림: backend error logs, security oauth failures
- 방문자 유발: 가능
- 확정: 2026-11-26 허정석

## 증상
Google 로그인 콜백에서 authorization code를 토큰으로 바꾸는 호출이 실패해 로그인이 오류로 끝난다. 컨트롤러가 `reason=server_error` WARN을 남기고 보안 알림(oauth failures)도 함께 울릴 수 있다.

## 원인
Google 토큰 응답 오류와 네트워크 오류가 모두 IOException이라 같은 줄로 남는다. `errorType`으로 구분한다.
- `TokenResponseException`: Google이 4xx로 거절했다. 잘못되거나 이미 쓴 code(invalid_grant), 클라이언트 설정 오류(invalid_client, redirect_uri_mismatch). 방문자가 code를 꾸며 콜백을 호출해도 이 줄이 남는다
- `UnknownHostException`, `SocketTimeoutException`, `SSL*`, `Connect*`: Mac mini에서 Google까지의 네트워크 문제

## 확인할 것
- `errorType`이 무엇인지, 한 번인지 여러 사용자에게서 반복되는지
- 네트워크 계열이면 같은 시각 다른 외부 호출(Gemini, Google Calendar)도 실패하는지
- `TokenResponseException`이 정상 사용자 로그인마다 나면 운영 env의 Google 클라이언트 설정(redirect URI, client secret)

## 조치
한두 번의 `TokenResponseException`은 잘못된 code일 가능성이 커서 조치가 필요 없다. 네트워크 계열은 회복을 기다린다. 설정 오류면 운영 env 값을 고쳐야 하고 이는 full 배포다. backend 재시작은 어느 경우에도 도움이 되지 않는다.
