# TaskFlow Calendar

> 구글 캘린더 연동 작업 관리 시스템 - 포트폴리오 프로젝트

## 프로젝트 개요

Task 관리와 Google Calendar를 동기화하는 웹 애플리케이션입니다.
외부 API 연동 시 발생할 수 있는 장애 상황에서도 데이터 일관성을 보장하기 위해 **Outbox 패턴**을 적용했습니다.

## 기술 스택

- **Backend**: Java 11, Spring Boot 2.7.18
- **ORM**: Spring Data JPA, Hibernate
- **Database**: PostgreSQL 14
- **Build Tool**: Gradle 8.10
- **Containerization**: Docker, Docker Compose

## 주요 기능 (예정)

- ✅ Task CRUD 및 상태 전이 관리
- ✅ Google Calendar 단방향 동기화
- ✅ Outbox 패턴을 통한 외부 API 안정성 보장
- ✅ Soft Delete 정책
- ✅ Task 변경 이력 추적

## 아키텍처 특징

### 1. Outbox 패턴
외부 API(Google Calendar) 호출 실패 시에도 내부 트랜잭션의 일관성을 보장합니다.
- Task 저장 트랜잭션 내에서 Outbox 레코드만 생성
- 별도 Worker가 비동기로 외부 API 호출
- Exponential Backoff를 통한 재시도 전략

### 2. 단방향 JPA 관계
- ManyToOne만 사용하여 N+1 문제 및 LazyLoading 이슈 방지
- 컬렉션 매핑 최소화

### 3. DTO 기반 API 설계
- Entity 직접 노출 금지
- 명확한 API 계약

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

- [x] **Week 1**: 프로젝트 세팅 및 기본 인프라 (2026-01-09)
- [ ] **Week 2**: Task 도메인 및 상태 전이
- [ ] **Week 3**: Outbox 패턴 구현
- [ ] **Week 4**: Google OAuth & Calendar API 연동
- [ ] **Week 5**: 관측 API 및 테스트
- [ ] **Week 6**: 프론트엔드 (React)

## 문서

- [프로젝트 명세서](./docs/taskflow_calendar_spec_v1_2_detailed.md) (추가 예정)
- [스프린트 체크리스트](./docs/SPRINT_CHECKLIST.md) (추가 예정)

## ERD (예정)
```
users (1) ─── (N) tasks
projects (1) ─── (N) tasks
tasks (1) ─── (N) task_history
tasks (1) ─── (N) calendar_outbox
users (1) ─── (1) oauth_google_tokens
```

## API 엔드포인트 (예정)

### Task Management
- `POST /api/projects/{projectId}/tasks` - Task 생성
- `GET /api/projects/{projectId}/tasks` - Task 목록 조회
- `GET /api/tasks/{taskId}` - Task 상세 조회
- `PATCH /api/tasks/{taskId}` - Task 수정
- `POST /api/tasks/{taskId}/status` - Task 상태 변경
- `DELETE /api/tasks/{taskId}` - Task 삭제 (Soft)

### Google Calendar Integration
- `GET /api/oauth/google/authorize` - OAuth 인증 URL
- `GET /api/oauth/google/callback` - OAuth 콜백
- `GET /api/integrations/google-calendar/status` - 연동 상태

### Observability
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

## 면접 대비 핵심 포인트

### Q: 왜 Outbox 패턴을 선택했나요?
**A**: 외부 API는 항상 실패할 수 있다고 가정했습니다. Task 저장 트랜잭션과 Google Calendar API 호출을 분리하여, API 장애 시에도 핵심 데이터(Task)는 반드시 보존되도록 설계했습니다.

### Q: JPA에서 양방향 관계를 사용하지 않은 이유는?
**A**: N+1 문제와 순환 참조 이슈를 사전에 방지하기 위해 단방향 ManyToOne만 사용했습니다. 필요한 데이터는 명시적인 조인 또는 별도 조회로 가져오는 것이 더 명확하고 예측 가능하다고 판단했습니다.

### Q: Soft Delete를 선택한 이유는?
**A**: Task 삭제 시에도 이력 추적이 필요하고, Google Calendar의 이벤트 삭제 작업이 비동기로 처리되기 때문에 물리적 삭제보다는 논리적 삭제가 적합하다고 판단했습니다.

## 라이선스

MIT License - 학습 및 포트폴리오 목적의 프로젝트입니다.

## 연락처

- GitHub: [@heojungseok](https://github.com/heojungseok)
- Email: tjrwjdgj@gmail.com
