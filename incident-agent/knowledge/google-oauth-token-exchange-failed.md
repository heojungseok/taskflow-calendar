# Google 로그인 토큰 교환 실패

- 식별 패턴: `ERROR c.t.i.oauth.GoogleOAuthService : Google token exchange failed. errorType=`
- 로거: `com.taskflow.identity.oauth.GoogleOAuthService`
- 관련 알림: backend error logs
- 확정: 대기

## 증상
Google 로그인 콜백에서 authorization code를 토큰으로 바꾸는 호출이 IO 예외로 실패하고 로그인이 오류로 끝난다.

## 원인
Google 토큰 엔드포인트까지의 네트워크 문제(DNS, TLS, 타임아웃)나 Google 일시 장애다. 클라이언트 설정이 틀리면 IO 예외가 아니라 응답 오류로 나타난다.

## 확인할 것
- 같은 시각 다른 외부 호출(Gemini, Google Calendar)도 실패하는지(Mac mini 네트워크 문제 여부)
- 몇 분 안에 저절로 멈췄는지

## 조치
네트워크나 Google 쪽이 회복되면 사용자가 다시 로그인하면 된다. backend 재시작은 도움이 되지 않는다. 계속되면 Mac mini의 외부 연결을 확인한다.
