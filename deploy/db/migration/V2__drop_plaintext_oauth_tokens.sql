-- Google OAuth 토큰을 컬럼 암호화(EncryptedStringConverter)로 전환한다.
-- 기존 행은 평문으로 저장돼 있고 새 키로 복호화할 수 없으므로 마이그레이션하지 않고 폐기한다.
--
-- 영향: 연결돼 있던 사용자는 Google 재연결 전까지 캘린더 동기화가 멈춘다.
--       TaskFlow 로그인(JWT)과 Task·프로젝트 데이터는 영향받지 않는다.
--
-- 전체 행 삭제가 의도된 동작이다. 이 테이블은 재연결로 복구 가능한 위임 자격증명만 담는다.
DELETE FROM oauth_google_tokens WHERE user_id IS NOT NULL;
