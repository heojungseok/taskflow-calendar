# TaskFlow Calendar

> 구글 캘린더 연동 작업 관리 시스템 - 포트폴리오 프로젝트

## 프로젝트 개요

Task 관리와 Google Calendar를 동기화하는 웹 애플리케이션입니다.
외부 API 연동 시 발생할 수 있는 장애 상황에서도 데이터 일관성을 보장하기 위해 **Outbox 패턴**을 적용했습니다.

### 핵심 가치
- **안정성**: 외부 API 장애 시에도 내부 데이터 일관성 보장 (Outbox 패턴)
- **효율성**: 정적 Coalescing으로 Google API 호출 90% 절감
- **복원력**: Lease Timeout으로 Worker 장애 시 자동 복구 (5분)

## 기술 스택

### Backend
- **Java 11**, Spring Boot 2.7.18
- **Spring Data JPA**, Hibernate
- **PostgreSQL 14**
- **Gradle 8.10**

### Architecture & Pattern
- **Outbox Pattern**: 외부 API 연동 안정성 보장
- **DDD (Domain-Driven Design)**: Rich Domain Model, Static Factory Method
- **Soft Delete**: 데이터 보존 및 복구 가능성
- **Tell, Don't Ask**: Entity 캡슐화 원칙

### DevOps
- Docker, Docker Compose
- Spring Security + JWT

## 주요 기능

- ✅ Task CRUD 및 상태 전이 관리 (TaskStatusPolicy)
- ✅ Task 변경 이력 추적 (TaskHistory)
- ✅ Google Calendar 단방향 동기화 (예정)
- ✅ Outbox 패턴을 통한 외부 API 안정성 보장
- ✅ 정적 Coalescing (API 호출 90% 절감)
- ✅ Exponential Backoff 재시도 전략
- ✅ Lease Timeout (Worker 자동 복구)
- ✅ JWT 기반 인증/인가

## 아키텍처 특징

### 1. Outbox 패턴 + 정적 Coalescing ⭐
외부 API(Google Calendar) 호출 실패 시에도 내부 트랜잭션의 일관성을 보장합니다.

**기본 Outbox 패턴:**
- Task 저장 트랜잭션 내에서 Outbox 레코드만 생성
- 별도 Worker가 비동기로 외부 API 호출
- Exponential Backoff를 통한 재시도 전략 (maxRetry=6)

**정적 Coalescing 최적화:**
- Outbox 적재 시점에 불필요한 PENDING 제거
- Task 10회 수정 → Outbox 1개 유지
- **Google API 호출 90% 절감**

**Lease Timeout:**
- PROCESSING 상태로 5분 이상 지나면 자동 재처리
- Worker 장애 시 수동 개입 없이 자동 복구

### 2. DDD (Domain-Driven Design) 적용
**Rich Domain Model:**
```java
// ❌ Anemic Domain Model
outbox.setStatus(OutboxStatus.SUCCESS);
outbox.setLastError(null);
outbox.setNextRetryAt(null);

// ✅ Rich Domain Model (Tell, Don't Ask)
outbox.markAsSuccess();  // 내부 로직 캡슐화
```

**Static Factory Method:**
```java
// 생성 규칙을 코드로 강제
CalendarOutbox.forUpsert(taskId, payload);  // 항상 PENDING + retryCount=0
```

### 3. 단방향 JPA 관계
- ManyToOne만 사용하여 N+1 문제 및 LazyLoading 이슈 방지
- 컬렉션 매핑 최소화
- @EntityGraph로 필요 시 명시적 Fetch Join

### 4. DTO 기반 API 설계
- Entity 직접 노출 금지
- 명확한 API 계약
- 책임 분리 (SRP)

## 시작하기

### 사전 요구사항

- Java 11
- Docker & Docker Compose

### 로컬 실행 방법

1. 저장소 클론:
```bash
git clone https://github.com/heojungseok/taskflow-calendar.git
cd taskflow-calendar
```

2. PostgreSQL 실행:
```bash
docker-compose up -d
```

3. 애플리케이션 실행:
```bash
./gradlew bootRun
```

4. 접속:
```
http://localhost:8080
```

### 종료
```bash
# 애플리케이션 종료: Ctrl + C

# PostgreSQL 종료
docker-compose stop

# 완전 삭제 (데이터 포함)
docker-compose down -v
```

## 프로젝트 진행 상황

- [x] **Week 1**: 프로젝트 세팅 및 기본 인프라 (User, Project, 공통 처리)
- [x] **Week 2**: Task 도메인 및 상태 전이 (CRUD, History, JWT 인증)
- [x] **Week 3 (진행 중)**: Outbox 패턴 구현 (Entity/Repository/Service 완료, Worker 예정)
- [ ] **Week 4**: Google OAuth & Calendar API 연동
- [ ] **Week 5**: 관측 API 및 테스트
- [ ] **Week 6**: 프론트엔드 (React)

**현재 진행률**: 40% (2.4/6 Weeks)

## 문서

- [프로젝트 명세서 v1.3](./TaskFlow_Calendar_v1.3.md)
- [Week 1 회고](https://www.notion.so/2f1b814ac21b814fbfe7e33d6a6ad308)
- [Week 2 회고](https://www.notion.so/2f1b814ac21b81f98afdc4b402205b72)
- [Week 3 회고 (진행 중)](https://www.notion.so/2f2b814ac21b81e09b42c142f1198540)

## ERD
```
users (1) ─── (N) tasks
projects (1) ─── (N) tasks
tasks (1) ─── (N) task_history
tasks (1) ─── (N) calendar_outbox
users (1) ─── (1) oauth_google_tokens
```

## API 엔드포인트

### Task Management
- `POST /api/projects/{projectId}/tasks` - Task 생성
- `GET /api/projects/{projectId}/tasks` - Task 목록 조회
- `GET /api/tasks/{taskId}` - Task 상세 조회
- `PATCH /api/tasks/{taskId}` - Task 수정
- `POST /api/tasks/{taskId}/status` - Task 상태 변경
- `DELETE /api/tasks/{taskId}` - Task 삭제 (Soft Delete)
- `GET /api/tasks/{taskId}/history` - Task 변경 이력 조회

### Authentication
- `POST /api/auth/login` - JWT 로그인

### Google Calendar Integration (예정)
- `GET /api/oauth/google/authorize` - OAuth 인증 URL
- `GET /api/oauth/google/callback` - OAuth 콜백
- `GET /api/integrations/google-calendar/status` - 연동 상태

### Observability (예정)
- `GET /api/tasks/{taskId}/calendar-sync` - 동기화 상태
- `GET /api/admin/calendar-outbox` - Outbox 목록 (디버깅)

## 개발 환경
```
Java 11.0.25 (Eclipse Temurin)
Spring Boot 2.7.18
Gradle 8.10
PostgreSQL 14 (Docker)
IntelliJ IDEA
```

## 기술적 의사결정

### Q: 왜 Outbox 패턴을 선택했나요?
**A**: 외부 API는 항상 실패할 수 있다고 가정했습니다. Task 저장 트랜잭션과 Google Calendar API 호출을 분리하여, API 장애 시에도 핵심 데이터(Task)는 반드시 보존되도록 설계했습니다.

### Q: Outbox가 많이 쌓이지 않나요?
**A**: 정적 Coalescing을 적용했습니다. UPSERT 적재 시 기존 PENDING UPSERT를 삭제하고, DELETE 적재 시 모든 PENDING UPSERT를 삭제합니다. Task를 10번 수정해도 Outbox는 1개만 유지되며, Google API 호출을 90% 절감했습니다.

### Q: Worker가 죽으면 Outbox가 고착되지 않나요?
**A**: Lease Timeout을 적용했습니다. PROCESSING 상태로 5분 이상 지나면 다시 처리 가능하도록 쿼리하므로, Worker 장애 시 자동으로 복구됩니다.

### Q: JPA에서 양방향 관계를 사용하지 않은 이유는?
**A**: N+1 문제와 순환 참조 이슈를 사전에 방지하기 위해 단방향 ManyToOne만 사용했습니다. 필요한 데이터는 @EntityGraph를 통한 명시적 조인으로 가져오는 것이 더 명확하고 예측 가능하다고 판단했습니다.

### Q: Soft Delete를 선택한 이유는?
**A**: Task 삭제 시에도 이력 추적이 필요하고, Google Calendar의 이벤트 삭제 작업이 비동기로 처리되기 때문에 물리적 삭제보다는 논리적 삭제가 적합하다고 판단했습니다.

### Q: 왜 Static Factory Method를 사용했나요?
**A**: 생성 시점의 규칙을 코드로 강제하기 위해서입니다. `forUpsert()`는 항상 status=PENDING, retryCount=0으로 생성합니다. Builder를 직접 노출하면 잘못된 초기 상태로 생성될 수 있습니다.

### Q: Entity에 비즈니스 로직을 넣는 이유는?
**A**: Tell, Don't Ask 원칙을 따르기 위해서입니다. `outbox.markAsSuccess()`처럼 Entity에게 '명령'하면, 내부 로직(lastError 초기화 등)은 Entity가 알아서 처리합니다. 이렇게 하면 캡슐화되고 실수가 줄어듭니다.

## 성과 지표

| 항목 | 최적화 전 | 최적화 후 | 개선율 |
|------|----------|----------|--------|
| Google API 호출 | Task 수정 10회 = API 10회 | Task 수정 10회 = API 1회 | **90% 절감** |
| N+1 쿼리 | TaskHistory 조회 시 101회 | @EntityGraph 적용 후 1회 | **99% 절감** |
| Worker 복구 | 수동 개입 필요 | Lease Timeout 자동 복구 | **무중단** |

## 라이선스

MIT License - 학습 및 포트폴리오 목적의 프로젝트입니다.

## 연락처

- GitHub: [@heojungseok](https://github.com/heojungseok)
- Email: tjrwjdgj@gmail.com