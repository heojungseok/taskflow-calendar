# TaskFlow Calendar - Project Context

## 프로젝트 개요
Google Calendar와 연동되는 Task Flow 관리 시스템. OAuth 2.0 인증과 자동 토큰 갱신을 지원하며, 캘린더 이벤트와 작업을 통합 관리합니다.

## 기술 스택
- **Backend**: Spring Boot 2.7.18 (Java 11)
- **Database**: PostgreSQL
- **Security**: Spring Security + JWT
- **OAuth**: Google OAuth 2.0 + Calendar API v3
- **Build Tool**: Gradle

## 핵심 기능
1. **Google OAuth 인증**
   - Authorization Code Flow
   - Access Token + Refresh Token 관리
   - 자동 토큰 갱신 (Proactive + Reactive)

2. **Google Calendar 통합**
   - 이벤트 조회/생성/수정/삭제
   - 양방향 동기화

3. **인증/인가**
   - JWT 기반 세션 관리
   - Spring Security 통합

## 디렉토리 구조
```
src/main/java/com/taskflow/
├── TaskFlowCalendarApplication.java  # Main
├── calendar/                          # Google Calendar 연동
├── common/                            # 공통 유틸리티
├── config/                            # 설정 클래스
├── security/                          # 인증/인가
├── service/                           # 비즈니스 로직
└── web/                               # Controller/DTO
```

## 주요 컴포넌트

### 1. OAuth 토큰 관리
- **Proactive Refresh**: 토큰 만료 5분 전 자동 갱신
- **Reactive Refresh**: API 호출 시 401 오류 발생 시 즉시 갱신
- **Token Storage**: DB 저장 (암호화 권장)

### 2. Calendar Service
- `CalendarService`: Google Calendar API 호출
- `TokenRefreshService`: 토큰 갱신 로직
- Dirty Checking 적용 (변경 감지)

### 3. Security
- JWT 발급/검증
- Spring Security FilterChain
- OAuth2 콜백 처리

## 개발 규칙

### Code Style
- **파일**: 최대 300줄
- **함수/메서드**: 최대 50줄, 단일 책임
- **네이밍**: Java 표준 (camelCase, PascalCase)
- **Formatting**: Google Java Style Guide

### Testing
- 단위 테스트: JUnit 5
- E2E 테스트: Week 4 완료
- 검증: `./gradlew test`

### Git Workflow
- **Main Branch**: `main`
- **Commit Message**:
  - `feat:` 새 기능
  - `fix:` 버그 수정
  - `refactor:` 리팩토링
  - `test:` 테스트 추가/수정

## 최근 작업 내역
- ✅ Google OAuth 토큰 자동 갱신 구현 (Proactive + Reactive)
- ✅ Week 4 E2E 테스트 완료
- ✅ Dirty Checking 재적용
- ✅ Calendar API 연동 완료

## 환경 변수
```bash
# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/taskflow
SPRING_DATASOURCE_USERNAME=your_username
SPRING_DATASOURCE_PASSWORD=your_password

# Google OAuth
GOOGLE_CLIENT_ID=your_client_id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your_client_secret
GOOGLE_REDIRECT_URI=http://localhost:8080/api/auth/oauth2/callback/google

# JWT
JWT_SECRET=your_jwt_secret_key
JWT_EXPIRATION=86400000
```

## 빌드 & 실행
```bash
# 빌드
./gradlew build

# 테스트
./gradlew test

# 실행
./gradlew bootRun

# 또는
java -jar build/libs/taskflow-calendar-0.0.1-SNAPSHOT.jar
```

## API 엔드포인트 (예상)
- `POST /api/auth/login` - 로그인
- `GET /api/auth/oauth2/authorize/google` - OAuth 시작
- `GET /api/auth/oauth2/callback/google` - OAuth 콜백
- `GET /api/calendar/events` - 이벤트 조회
- `POST /api/calendar/events` - 이벤트 생성
- `PUT /api/calendar/events/{id}` - 이벤트 수정
- `DELETE /api/calendar/events/{id}` - 이벤트 삭제

## 주의사항
1. **토큰 보안**: Refresh Token은 암호화하여 저장
2. **API Rate Limit**: Google Calendar API 쿼터 관리
3. **에러 핸들링**: OAuth 에러, API 에러 적절히 처리
4. **동시성**: 토큰 갱신 시 Race Condition 방지

## 개선 예정
- [ ] Refresh Token 암호화
- [ ] 이벤트 캐싱
- [ ] Webhook 기반 실시간 동기화
- [ ] 다중 캘린더 지원
