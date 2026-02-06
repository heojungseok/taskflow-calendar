# TaskFlow Calendar - Project Context

## 프로젝트 개요
Google Calendar와 연동되는 Task 관리 시스템. Outbox 패턴으로 외부 API 장애에도 데이터 일관성을 보장하며, 정적 Coalescing으로 API 호출을 최적화합니다.

## 기술 스택
- **Backend**: Spring Boot 2.7.18 (Java 11)
- **Database**: PostgreSQL 14
- **ORM**: Spring Data JPA, Hibernate
- **Security**: Spring Security + JWT
- **OAuth**: Google OAuth 2.0 + Calendar API v3
- **Build Tool**: Gradle 8.10
- **Container**: Docker, Docker Compose

## 핵심 기능
1. **Task 관리**
    - CRUD 및 상태 전이 (TaskStatusPolicy)
    - 변경 이력 추적 (TaskHistory)
    - Soft Delete

2. **Google Calendar 통합**
    - OAuth 2.0 인증 (Authorization Code Flow)
    - 단방향 동기화 (Task → Calendar)
    - Access Token + Refresh Token 자동 갱신
    - 이벤트 생성/수정/삭제

3. **Outbox 패턴**
    - 외부 API 안정성 보장
    - 정적 Coalescing (4 Rules: A-1, A-2, B-1, B-2)
    - Exponential Backoff (최대 6회 재시도)
    - Lease Timeout (5분, Worker 자동 복구)

4. **인증/인가**
    - JWT 기반 세션 관리
    - Spring Security FilterChain

## 디렉토리 구조
```
src/main/java/com/taskflow/backend/
├── TaskflowBackendApplication.java    # Main
├── common/                             # 공통 (ApiResponse, ErrorCode, Exception)
├── domain/
│   ├── task/                           # Task 도메인
│   │   ├── Task.java
│   │   ├── TaskRepository.java
│   │   ├── TaskService.java
│   │   └── TaskStatusPolicy.java
│   ├── history/                        # TaskHistory
│   ├── outbox/                         # CalendarOutbox
│   │   ├── CalendarOutbox.java
│   │   ├── CalendarOutboxRepository.java
│   │   └── CalendarOutboxService.java
│   └── oauth/                          # GoogleOAuthToken
│       ├── GoogleOAuthToken.java
│       └── GoogleOAuthService.java
├── integration/
│   └── googlecalendar/                 # Google Calendar API
│       ├── GoogleCalendarClient.java
│       ├── GoogleCalendarService.java
│       ├── GoogleCalendarMapper.java
│       ├── RetryableIntegrationException.java
│       └── NonRetryableIntegrationException.java
├── worker/
│   └── CalendarOutboxWorker.java      # Outbox 처리 Worker
├── config/                             # 설정
├── security/                           # JWT, Spring Security
└── web/                                # Controller, DTO
```

## 주요 컴포넌트

### 1. Outbox 패턴 + 정적 Coalescing
**CalendarOutbox**: Task 변경 시 Outbox에 동기화 요청 기록
- UPSERT: 이벤트 생성/수정
- DELETE: 이벤트 삭제

**정적 Coalescing (4개 규칙)**:
| Rule | 적재 시점 | 대상 | 목적 |
|------|----------|------|------|
| **A-1** | UPSERT | PENDING DELETE 삭제 | 도메인 충돌 해소 |
| **A-2** | UPSERT | PENDING UPSERT 삭제 | 최신 1개만 유지 |
| **B-1** | DELETE | PENDING UPSERT 삭제 | 도메인 충돌 해소 + 효율성 |
| **B-2** | DELETE | PENDING DELETE 중복 방지 | 효율성 |

**효과**:
- Task 10회 수정 → Outbox 1개 유지
- Google API 호출 90% 절감
- DB 저장 공간 90% 절감
- Worker 처리 부하 90% 절감

**Worker (CalendarOutboxWorker)**:
- 5초마다 Polling
- PENDING/FAILED 상태 조회
- Lease Timeout (5분) 적용
- Exponential Backoff (최대 6회)

**Race Condition 방어**:
- `deleteByTaskIdAndStatusAndOpType(taskId, PENDING, opType)`
- PROCESSING 상태는 자동 제외
- Worker 처리 중인 작업 보호

### 2. OAuth 토큰 관리
**GoogleOAuthService**:
- Authorization URL 생성
- 콜백 처리 (code → token 교환)
- Token 저장 (GoogleOAuthToken Entity)

**Token 자동 갱신**:
- **Reactive Refresh**: API 호출 시 401 발생 시 즉시 갱신
- Refresh Token으로 Access Token 재발급
- 갱신 실패 시 Outbox FAILED 처리

### 3. Google Calendar Service
**GoogleCalendarService**:
- `handle(CalendarOutbox)`: Outbox 처리
- UPSERT: 이벤트 생성/수정 (eventId 유무로 분기)
- DELETE: 이벤트 삭제 (eventId 없으면 no-op)

**예외 분류**:
- **RetryableIntegrationException**: 5xx, 429, 네트워크 타임아웃
- **NonRetryableIntegrationException**: 400, 401, 403

**동적 Coalescing**:
- Worker 처리 시 Task 최신 상태 조회
- Payload가 아닌 Task를 Source of Truth로 사용
- 삭제/비활성화된 Task는 자동 skip

### 4. Task 도메인
**Task Entity**:
- Rich Domain Model (Tell, Don't Ask)
- Static Factory Method
- 상태 전이 규칙 (TaskStatusPolicy)

**TaskService**:
- Task CRUD
- 상태 전이 (`changeStatus()`)
- Outbox 트리거 (`enqueueUpsert()`, `enqueueDelete()`)

**TaskHistory**:
- 변경 유형: STATUS/ASSIGNEE/SCHEDULE/CONTENT
- before/after 값 JSON 저장

### 5. Security
**JWT**:
- 발급: `/api/auth/login`
- 검증: `JwtAuthenticationFilter`
- 만료: 24시간

**Spring Security**:
- FilterChain 설정
- 인증/인가 처리

## 개발 규칙

### Code Style
- **파일**: 최대 300줄
- **함수/메서드**: 최대 50줄, 단일 책임
- **네이밍**: Java 표준 (camelCase, PascalCase)
- **Formatting**: Google Java Style Guide

### JPA 규칙
- 단방향 ManyToOne만 사용
- 컬렉션 매핑 최소화
- Entity 직접 노출 금지 (DTO 사용)
- @EntityGraph로 명시적 Fetch Join

### Testing
- 단위 테스트: JUnit 5 + Mockito
- 통합 테스트: @DataJpaTest
- E2E 테스트: Week 4 완료 (실제 Google Calendar API)
- 검증: `./gradlew test`

### Git Workflow
- **Main Branch**: `main`
- **Commit Message**:
    - `feat:` 새 기능
    - `fix:` 버그 수정
    - `refactor:` 리팩토링
    - `test:` 테스트 추가/수정
    - `docs:` 문서 수정

## 최근 작업 내역 (Week 4 완료)
- ✅ Google OAuth 2.0 인증 구현
- ✅ Google Calendar API 연동 (CRUD)
- ✅ Token 자동 갱신 (Reactive: 401 감지 시)
- ✅ Outbox Worker 구현 (Polling, Lease Timeout)
- ✅ 정적 Coalescing 4개 규칙 구현 (A-1, A-2, B-1, B-2)
- ✅ 도메인 충돌 방지 (UPSERT ↔ DELETE)
- ✅ Race Condition 방어 (PROCESSING 보호)
- ✅ E2E 테스트 완료 (실제 Google Calendar API)
- ✅ 동적 Coalescing (Task Source of Truth)

## 환경 변수
```bash
# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/taskflow
SPRING_DATASOURCE_USERNAME=taskflow_user
SPRING_DATASOURCE_PASSWORD=taskflow_password

# Google OAuth
GOOGLE_CLIENT_ID=your_client_id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your_client_secret
GOOGLE_REDIRECT_URI=http://localhost:8080/api/oauth/google/callback

# JWT
JWT_SECRET=your_jwt_secret_key_at_least_256_bits
JWT_EXPIRATION=86400000

# Worker
OUTBOX_WORKER_FIXED_DELAY=5000
OUTBOX_WORKER_MAX_RETRY=6
OUTBOX_WORKER_LEASE_TIMEOUT_MINUTES=5
```

## 빌드 & 실행
```bash
# PostgreSQL 시작 (Docker)
docker-compose up -d

# 빌드
./gradlew build

# 테스트
./gradlew test

# 실행
./gradlew bootRun

# 종료 후 정리
docker-compose down -v
```

## API 엔드포인트

### Task Management
- `POST /api/projects/{projectId}/tasks` - Task 생성
- `GET /api/projects/{projectId}/tasks` - Task 목록 조회
- `GET /api/tasks/{taskId}` - Task 상세 조회
- `PATCH /api/tasks/{taskId}` - Task 수정
- `POST /api/tasks/{taskId}/status` - Task 상태 변경
- `DELETE /api/tasks/{taskId}` - Task 삭제 (Soft Delete)
- `GET /api/tasks/{taskId}/history` - Task 변경 이력

### Authentication
- `POST /api/auth/login` - JWT 로그인

### Google OAuth & Calendar
- `GET /api/oauth/google/authorize` - OAuth 인증 URL
- `GET /api/oauth/google/callback` - OAuth 콜백
- `GET /api/integrations/google-calendar/status` - 연동 상태

### Observability (디버깅)
- `GET /api/tasks/{taskId}/calendar-sync` - Task 동기화 상태
- `GET /api/admin/calendar-outbox` - Outbox 목록

## 데이터 모델 (ERD)
```
users (1) ─── (N) tasks
projects (1) ─── (N) tasks
tasks (1) ─── (N) task_history
tasks (1) ─── (N) calendar_outbox
users (1) ─── (1) oauth_google_tokens
```

**주요 테이블**:
- `users`: 사용자 정보
- `projects`: 프로젝트
- `tasks`: 작업 (deleted, deleted_at 포함)
- `task_history`: 변경 이력
- `calendar_outbox`: 동기화 요청 (status, op_type, payload, retry_count, next_retry_at)
- `oauth_google_tokens`: Google OAuth 토큰 (access_token, refresh_token, expiry_at)

**핵심 인덱스**:
- `IDX_outbox_status_next_retry`: Worker 조회용
- `IDX_outbox_task_status_optype`: 정적 Coalescing용
- `IDX_tasks_project_deleted`: Task 목록 조회용

## 주의사항

### 1. Outbox 패턴
- Task 저장 트랜잭션에 Google API 호출 금지
- Outbox 적재만 트랜잭션에 포함
- Worker가 비동기로 처리

### 2. 정적 Coalescing
- PENDING 상태만 삭제 대상
- PROCESSING 상태 절대 삭제 금지
- 도메인 충돌 (UPSERT ↔ DELETE) 방지

### 3. Token 관리
- Refresh Token 암호화 권장 (운영 환경)
- 401 발생 시 자동 갱신
- 갱신 실패 시 수동 개입 필요

### 4. API Rate Limit
- Google Calendar API 쿼터 관리
- 정적 Coalescing으로 호출 최소화
- Rate Limit 발생 시 Backoff

### 5. 동시성
- Worker 여러 대: Optimistic Lock (조건부 UPDATE)
- Lease Timeout: 5분
- Race Condition: PROCESSING 보호

## 개선 예정 (Week 5~6)

### Week 5 (진행 중)
- [ ] 정적 Coalescing 테스트 케이스 5~8 (Race Condition 검증)
- [ ] 관측 API 추가 검증
- [ ] Spec 문서 업데이트 (v1.4.0)
- [ ] Week 5 회고 작성

### Week 6
- [ ] 프론트엔드 구현 (React + TypeScript)
- [ ] Task 생성/수정/삭제 UI
- [ ] Calendar 동기화 상태 표시
- [ ] Outbox 모니터링 대시보드

### Future
- [ ] Refresh Token 암호화
- [ ] Proactive Token Refresh (만료 5분 전 자동 갱신)
- [ ] Event Caching (Calendar 동기화 최적화)
- [ ] Webhook 기반 실시간 동기화
- [ ] 다중 캘린더 지원

## 문서
- [프로젝트 Spec (v1.4.0)](https://www.notion.so/TaskFlow-Calendar-v1-4-0)
- [Week 1 회고](https://www.notion.so/2f1b814ac21b814fbfe7e33d6a6ad308)
- [Week 2 회고](https://www.notion.so/2f1b814ac21b81f98afdc4b402205b72)
- [Week 3 회고](https://www.notion.so/2f2b814ac21b81e09b42c142f1198540)
- [Week 4 회고](https://www.notion.so/2f1b814ac21b8178be7dd9936ae6aa78)

## 성과 지표

| 항목 | 최적화 전 | 최적화 후 | 개선율 |
|------|----------|----------|--------|
| Google API 호출 | Task 수정 10회 = 10회 | Task 수정 10회 = 1회 | **90% 절감** |
| DB 저장 공간 (Outbox) | 10개 | 1개 | **90% 절감** |
| Worker 처리 부하 | 10개 조회/처리 | 1개 조회/처리 | **90% 절감** |
| N+1 쿼리 | 101회 | 1회 (@EntityGraph) | **99% 절감** |
| Worker 복구 | 수동 개입 | Lease Timeout 자동 | **무중단** |
| 도메인 충돌 | 발생 가능 | Rule A-1/B-1로 차단 | **100% 방지** |

## 면접 대비 핵심 설명

### Q: Outbox 패턴을 왜 선택했나요?
**A**: 외부 API는 항상 실패할 수 있다고 가정했습니다. Task 저장과 Google Calendar API 호출을 분리하여, API 장애 시에도 핵심 데이터는 반드시 보존되도록 설계했습니다.

### Q: Outbox가 많이 쌓이지 않나요?
**A**: 정적 Coalescing 4개 규칙으로 불필요한 Outbox를 적재 시점에 삭제합니다. Task를 10번 수정해도 Outbox는 1개만 유지되며, DB 공간과 Worker 부하를 90% 절감했습니다.

### Q: 정적 Coalescing 중 Worker가 처리하면 어떻게 되나요?
**A**: PROCESSING 상태는 삭제 대상에서 자동 제외됩니다. `deleteByTaskIdAndStatusAndOpType()`에 `status = PENDING` 조건이 있어서 Worker가 선점한 Outbox는 보호됩니다.

### Q: DELETE와 UPSERT가 동시에 존재하면?
**A**: 도메인 충돌이므로 정적 Coalescing이 방지합니다. UPSERT 적재 시 PENDING DELETE 삭제(A-1), DELETE 적재 시 PENDING UPSERT 삭제(B-1)로 항상 하나의 명확한 의도만 유지합니다.

### Q: Worker가 죽으면 Outbox가 고착되지 않나요?
**A**: Lease Timeout 5분을 적용했습니다. PROCESSING 상태로 5분 이상 지나면 다시 처리 가능하도록 쿼리하므로 자동 복구됩니다.

### Q: 정적 vs 동적 Coalescing 차이는?
**A**: 정적은 '적재 시점'에 불필요한 Outbox를 DB에서 삭제해서 공간과 부하를 줄입니다. 동적은 '처리 시점'에 Task 최신 상태를 조회해서 최종 상태만 반영합니다. 둘 다 API 호출은 1번이지만, 정적이 DB 효율성을 추가 확보합니다.