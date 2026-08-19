---
title: "TaskFlow 공개 데모·Docker Desktop 배포 상세 설계"
created: 2026-08-19
updated: 2026-08-19
status: reviewing
plan_owner: Codex
target_repository: /Users/heojungseok/.codex/worktrees/a20d/taskflow-calendar
baseline_branch: main
baseline_commit: 2bf04bfb4b24093b44931630b89c312e98c164fb
working_branch: codex/taskflow-public-demo-deployment-design
git_topology: single-source-isolated-worktree
risk_level: 높음
risk_score: 7
forced_high: true
review_round_mode: gate-5-7-combined-if-safe
cleanup_approval: pending
approval_basis: "Gate 2 리뷰 후 사용자 승인 대기"
---

# TaskFlow 공개 데모·Docker Desktop 배포 상세 설계

> 이 문서는 구현 전 설계다. `[현재 사실]`만 현재 코드와 환경을 설명하며 `[목표 상태]`와 `[검증 예정]`은 아직 구현·실행된 결과가 아니다.

## Gate 0 — 기준 상태

[현재 사실]

| 항목 | 값 | 근거 |
|---|---|---|
| 대상 저장소 | `taskflow-calendar` 격리 worktree | 문서 frontmatter의 `target_repository` |
| 기준 소스 | `main` | `git branch -vv` |
| 기준 commit | `2bf04bfb4b24093b44931630b89c312e98c164fb` | `git rev-parse main origin/main` |
| 작업 상태 | 설계 작성 전 clean, 기준 commit과 `main`·`origin/main` 동일 | `git status --porcelain`, `git rev-parse` |
| 작업 브랜치 | `codex/taskflow-public-demo-deployment-design` | 격리 worktree에서 생성 |
| 보존 자산 | 기존 stash 3개와 주 작업공간의 `main` worktree | `git stash list`, `git worktree list --porcelain` |
| 배포 자산 | PostgreSQL 개발용 Compose와 PR 검증용 GitHub Actions만 존재. 백엔드·프론트 Dockerfile, Nginx, Prometheus, Grafana, CI/CD 배포 자산은 없음 | `docker-compose.yml`, `.github/workflows/ci.yml`, `git ls-files` |
| 조사 환경 | macOS, Docker Desktop 배포 목표, 2026-08-19 | 사용자 결정과 현재 task 환경 |

### Git 통합·보존 packet

[현재 사실]

- `main`과 `origin/main`은 같은 SHA다.
- 주 작업공간은 `main`을 점유하고 있으므로 설계와 이후 승인된 작업은 현재 격리 worktree의 `codex/` 브랜치에서만 수행한다.
- `stash@{0}`~`stash@{2}`는 이번 설계의 입력이 아니며 삭제·적용하지 않는다.
- push, merge, PR 생성, branch·stash·worktree cleanup은 이번 Gate 2 범위가 아니다. 각각 별도 승인 경계를 유지한다.

### 사용자 원요청

[현재 사실]

- 목적: 현재 Mac mini의 Docker Desktop에서 TaskFlow를 추후 공개 배포할 수 있게 만들고, 방문자별 깨끗한 임시 환경과 실제 Outbox `SKIPPED` 흐름을 보여준다.
- 포함: 인증 경계 보강, 방문자별 24시간 임시 사용자, Docker 배포, Prometheus·Grafana·Discord 관측, Cloudflare Tunnel과 Google OAuth 배포 검증.
- 비범위: Deep Security scan, 공유 Google 데모 계정, 가짜 Google Provider, Kubernetes, 다중 인스턴스, CI/CD 자동 배포, 현재 로컬 DB·OAuth 토큰·Docker volume 이전 또는 삭제.
- 변경 원칙: 구현 전 독립 리뷰와 사용자 승인, 구현 시 최소 root-cause patch와 최소 회귀 테스트, PR 생성 전 초안 제시.

## Gate 1 — 변경 분석

### 1. 목적과 범위

[목표 상태]

하나의 공개 origin에서 Nginx가 React 정적 파일과 `/api`를 제공한다. 방문자는 Google 계정 없이 자신만의 24시간 데모 사용자로 프로젝트와 Task를 만들고, 실제 60초 Outbox worker가 `PENDING → SKIPPED(no_google_link)`로 처리하는 모습을 확인한다. Google 테스트 사용자는 별도 OAuth 경로로 실제 Calendar 연동을 사용할 수 있다.

구현은 다음 세 단계로 묶는다.

1. **공개 애플리케이션 경계**: OAuth state, HttpOnly 세션 cookie와 CSRF, 임시 사용자, 데모 AI 비용 차단, 수동 worker 트리거 제거, 민감 로그 축소.
2. **Docker Desktop 배포·관측**: Nginx, backend, PostgreSQL/pgvector, 배포 전용 휘발성 Redis, Prometheus, Grafana, 최소 권한 DB 역할, Discord alert, 공급망 고정.
3. **공개 배포·증거**: Cloudflare Tunnel, HTTPS callback, 외부 E2E, 알림·재시작·rollback 검증과 문서 동기화.

### 2. 현재 확인된 사실

[현재 사실]

| 사실 | 근거 |
|---|---|
| 모든 API는 JWT bearer 인증이며 CSRF가 꺼져 있다 | `SecurityConfig.java:31-45`, `JwtAuthenticationFilter.java:32-60` |
| OAuth JWT가 redirect query에 들어가고 프론트가 localStorage에 저장한다 | `GoogleOAuthController.java:88-98`, `OAuthCallback.tsx:15-23`, `authStore.ts:28-55` |
| OAuth state는 전역 in-memory map이며 browser binding과 용량 제한이 없다 | `OAuthStateStore.java:19-46` |
| 프로젝트·Task·Outbox 조회는 현재 사용자 소유 범위로 제한된다 | `ProjectRepository.java:9-12`, `TaskRepository.java:13-31`, `OutboxController.java:25-45` |
| worker는 60초 기본 주기로 실제 실행되고 Google token이 없으면 `SKIPPED`로 끝낸다 | `CalendarOutboxWorker.java:42-55,103-168` |
| 인증 사용자 누구나 수동으로 전역 worker를 실행할 수 있다 | `OutboxController.java:48-53` |
| profile을 명시하지 않으면 `local`이 활성화되고, local 전용 Calendar test API는 body의 임의 `userId`로 Google Calendar client를 호출한다 | `application.yml:5-7`, `CalendarTestController.java:15-35` |
| Outbox UI가 진행 중일 때 10초 조건부 polling을 하며 수동 실행 버튼도 제공한다 | `OutboxPage.tsx:138-178` |
| `User.provider`가 이미 enum column이어서 `DEMO`를 추가해도 DB column 추가가 필요 없다 | `User.java:26-28`, `Provider.java:7-10` |
| Task 이력은 User FK를 요구하므로 임시 사용자도 실제 User row여야 한다 | `TaskHistory.java:25-31`, `TaskService.java:346-358` |
| 검색 embedding은 Task FK의 `ON DELETE CASCADE`를 사용한다 | `TaskSearchEmbeddingStore.java:171-179` |
| 개발 DB 기본 비밀번호와 `5432:5432` host publish가 존재한다 | `application.yml:13-17`, `docker-compose.yml:7-14` |
| 배포 profile은 명시하지 않으면 `local`이고 JPA 기본은 `update`다 | `application.yml:5-7,20-24` |
| SQL·binder 로그와 일부 OAuth email/name, 검색문, Task title이 로그에 남을 수 있다 | `application.yml:30-35`와 `log.*` 호출 조사 |
| Actuator·Prometheus registry와 배포용 컨테이너 자산은 아직 없다 | `build.gradle`, 저장소 파일 목록 |
| AI 주간 요약과 추천은 `WEEKLY_SUMMARY_CACHE_ENABLED=true`일 때 같은 `REDIS_URL`의 Lettuce cache를 사용한다. 주간 요약 TTL은 기본 3일, 추천 TTL은 90초다 | `application.yml:94-97`, `RedisWeeklySummaryCacheService.java:21-44`, `RedisTaskRecommendationCacheService.java:15-39` |
| 현재 local `.env`의 Redis endpoint는 Upstash Free Tier `context DB`와 일치한다. 2026-08-19 콘솔 확인 시 10/500,000 commands, 86KB/256MB였고 `agent`, `ctx` 계열만 보여 TaskFlow 전용 DB가 아니다 | Upstash Console read-only 확인과 token을 제외한 endpoint host 대조 |
| cache value에는 project 이름, AI 요약·highlight·risk·next action과 추천 Task 제목·마감·Calendar event ID가 직렬화된다 | `WeeklySummaryResponse.java:13-30`, `WeeklySummarySectionResponse.java:10-16`, `ProjectTaskRecommendationItemResponse.java:11-25` |
| Upstash는 cache도 disk에 지속하며 Free Tier는 encryption at rest·Prod Pack 대상이 아니다. 비활성 Free DB는 최소 14일 뒤 backup을 만든 후 archive될 수 있다 | Upstash `durability`, `security`, `FAQ`, `production-checklist` 공식 문서 |

### 3. 아직 검증되지 않은 가정

[가정]

| 가정 | 확인 방법 | 틀렸을 때 영향 |
|---|---|---|
| Mac mini의 Docker Desktop이 로그인 후 자동 시작되고 Compose stack을 상시 유지할 수 있다 | 3단계에서 재부팅 후 실제 기동 확인 | host service 또는 운영 방식 재설계 필요 |
| 기존 host의 `cloudflared` Named Tunnel 패턴을 재사용할 수 있다 | 3단계에서 tunnel route와 서비스 상태 확인 | compose에 cloudflared를 넣거나 다른 ingress 선택 필요 |
| 공개 origin은 하나이며 프론트와 API를 같은 origin으로 제공할 수 있다 | 도메인 확정 후 Nginx·Cloudflare route 확인 | CORS와 cookie 정책을 다시 설계해야 함 |
| Google OAuth는 당분간 Testing 또는 등록된 사용자 범위다 | Google Cloud Console에서 게시 상태 확인 | Production 심사 범위를 별도 과업으로 추가해야 함 |
| Discord webhook을 Grafana contact point가 직접 호출할 수 있다 | 테스트 alert 발송 및 Discord 수신 확인 | Grafana 버전/Discord payload 호환 설정 조정 필요 |
| Docker Desktop에 Redis를 포함한 6개 장기 실행 container를 Mac mini 자원 안에서 유지할 수 있다 | 2단계에서 idle·AI cache 부하의 CPU·memory·disk를 측정 | Redis `maxmemory`와 container memory limit을 낮추거나 관측 stack을 별도 재검토 |

### 4. 목표 상태

[목표 상태]

| 목표 | 현재와 달라지는 점 | 완료 판단 |
|---|---|---|
| browser-bound OAuth state | query state만 확인하던 것을 HttpOnly state cookie와 비교하고 서버 state를 10분·1,000개로 제한 | mismatch·만료·재사용·용량 초과 테스트 통과 |
| 안전한 browser session | JWT query/localStorage/Bearer를 HostOnly HttpOnly Secure SameSite=Lax cookie로 교체 | URL·localStorage에 JWT가 없고 인증·logout·만료 E2E 통과 |
| CSRF 보호 | cookie 인증의 unsafe method에 Spring Security CSRF 적용 | token 없음/오류 403, 정상 token 요청 성공 |
| 방문자별 24시간 사용자 | 공유 데이터가 아니라 `Provider.DEMO` User를 방문자마다 생성 | 서로 다른 browser context가 서로의 ID를 404로 보고 만료 cleanup 통과 |
| 비용 없는 데모 AI | DEMO 사용자는 Gemini·embedding client에 도달하지 않고 로컬 결과/명시적 demo 상태 사용 | mock 호출 0회 테스트와 UI 상태 확인 |
| scheduler-only worker | 수동 전역 trigger API와 UI 버튼 제거 | 공개 API에서 전역 worker를 직접 실행할 수 없음 |
| 분리된 배포 DB | 기존 local volume을 복사하지 않고 별도 이름의 fresh volume과 최소 권한 runtime role 사용 | volume 이름·role 권한·데이터 분리 확인 |
| 내부 관측 | Prometheus/Grafana는 외부에 공개하지 않고 Discord로 집계 alert만 발송 | 외부 route 차단, scrape·dashboard·alert E2E 통과 |
| fail-closed profile | profile 누락 시 local test bean이 뜨는 기본값 제거, prod Compose는 `prod` 고정 | profile 미지정·prod 모두 `/api/test/**` 404, local만 명시적으로 활성화 |
| 배포 전용 휘발성 cache | 현재 local Upstash 설정은 건드리지 않고 `compose.production.yml`에 memory-only Redis를 둔다 | Redis host port·volume·RDB·AOF가 없고 backend 전용 internal network에서만 접근, 장애 시 core 기능 유지 |

### 5. 기존 설계의 전제

[현재 사실]

| 전제 | 현재도 유효한가 | 처리 |
|---|---|---|
| 영속적인 실제 사용자 계정은 Google OAuth만 사용한다 | 예 | 이메일·비밀번호 로그인을 되살리지 않는다. DEMO는 24시간 내부 identity이며 실제 회원 가입 수단이 아니다 |
| 공개 데모는 Google 계정을 공유하지 않는다 | 예. 기존 Wiki의 “미리 만든 공유 demo user” 표현은 폐기 | 방문자별 DEMO identity로 대체하고 Gate 8에서 Wiki를 동기화한다 |
| worker는 앱 내부 `@Scheduled`, 기본 60초다 | 예 | 수동 bypass만 제거한다 |
| UI는 진행 중 상태가 있을 때만 polling한다 | 예 | `SKIPPED` 도달 후 polling을 멈춘다 |
| 데모 사용자는 live Gemini를 호출하지 않는다 | 예 | 서버에서 identity 기준으로 강제한다 |
| 프론트는 별도 Nginx container가 정적 파일과 `/api` proxy를 담당한다 | 예 | same-origin 경계를 만든다 |

### 6. 제약

[제약]

- 상시 공개지만 Mac mini 단일 host이므로 다중 backend·Kubernetes·고가용성은 범위 밖이다.
- 공개 방문자에게 Google Calendar 동의를 요구하지 않는다. Google Testing 정책과 7일 authorization expiry는 데모 identity에 영향을 주지 않는다.
- 공개 배포 cache는 가용성 필수 조건이나 원본 저장소로 사용하지 않는다. Redis cache loss는 허용하고 PostgreSQL만 영속 원본으로 둔다.
- 현재 local Upstash `context DB`, credential, key는 이번 배포 구현·검증의 읽기·쓰기·이전·삭제 대상이 아니다. local cache provider 변경은 별도 과업이다.
- 배포 Redis는 Docker host memory의 평문 데이터를 다루지만 RDB·AOF·volume을 모두 끈다. Docker host 관리자와 memory/swap 접근은 단일 host 잔여 위험이다.
- 현재 local DB, OAuth token, named volume, stash를 배포 DB로 복사하거나 삭제하지 않는다.
- secret 원문은 Git, 문서, 로그, Discord에 기록하지 않는다.
- 외부 Cloudflare·Google·Discord 상태 변경은 해당 실행 시점에 사용자 승인을 받는다.
- 리뷰어는 첫 라운드에서 파일을 수정하지 않는다. 주 작업자만 finding을 재검증하고 반영한다.

### 7. 목표 변경으로 깨지는 전제

[현재 사실]

| 깨지는 전제 | 원인 | 대응 |
|---|---|---|
| bearer JWT이므로 CSRF가 불필요하다 | HttpOnly cookie 인증으로 변경 | Spring CSRF cookie repository와 unsafe method 검증 도입 |
| JWT payload를 프론트가 직접 읽어 사용자 상태를 만든다 | JWT가 HttpOnly로 바뀜 | `/api/auth/session`이 인증 여부·user type·만료만 반환 |
| 임의 인증 사용자가 worker를 수동 실행할 수 있다 | 공개 다중 사용자 | trigger endpoint와 UI button 삭제, scheduler만 유지 |
| DB app 계정이 schema까지 임의 변경할 수 있다 | 공개 배포 최소 권한 | Flyway migration owner와 runtime DML role 분리, prod `validate` |
| 모든 인증 사용자는 live AI 호출 가능하다 | 익명 공개 demo | DEMO identity에서 live client 진입을 서버가 차단 |
| local profile 누락은 개발 편의로 안전하다 | 공개 Compose가 환경변수를 빠뜨릴 수 있음 | 기본 local 활성화를 제거하고 local을 개발자가 명시 |

### 8. 전체 영향 범위

| 상태 | 영역 | 영향 |
|---|---|---|
| 목표 상태 | 인증·API | OAuth authorize/callback response, demo session·logout·session endpoint, cookie JWT filter, CSRF |
| 목표 상태 | 데이터 | `Provider.DEMO`, 임시 User와 소유 Project/Task/History/Outbox의 batch hard-delete |
| 목표 상태 | 비동기 | 기존 worker 처리 로직 재사용, 수동 trigger 제거, metrics 추가 |
| 목표 상태 | AI | demo local-only 분기, Google user의 기존 live 경로 유지 |
| 목표 상태 | 프론트 | demo 진입, server session bootstrap, localStorage 제거, `SKIPPED` 필터·설명 |
| 목표 상태 | 네트워크 | Nginx만 `127.0.0.1`에 publish, DB/Redis/Prometheus/backend는 Docker network 전용, Grafana는 loopback 전용 |
| 목표 상태 | 운영 | Dockerfile·배포 Compose·healthcheck·migration·metrics·dashboard·alert·rollback |
| 목표 상태 | 문서 | 이 설계, 단계별 리뷰 ledger, local/CI/deployed 증거, Wiki 결정 동기화 |
| 목표 상태 | 개발 profile | `/api/test/**`는 명시적 local profile에서만 존재하고 배포 누락은 fail-closed |

### 9. 대안과 선택 이유

[결정]

| 대안 | 장점 | 단점·위험 | 선택 |
|---|---|---|---|
| 단일 구현자 + 단계별 reviewer, 최종 2인 gate | 충돌이 적고 독립성 유지, 사용량 절감 | 병렬 구현보다 느림 | **선택** |
| 구현자 2명 + reviewer 1명 | 일부 병렬화 | 인증·데이터·Compose 경계 충돌, 독립 검증 감소 | 제외 |
| 매 단계 reviewer 2명 | 맹점 감소 | 반복 비용이 크고 변경되지 않은 owner를 재검토 | 제외 |

| 기술 대안 | 장점 | 단점·위험 | 선택 |
|---|---|---|---|
| HttpOnly JWT cookie + CSRF | 기존 JWT 재사용, URL/localStorage 노출 제거 | CSRF token 흐름 추가 | **선택** |
| 서버 HttpSession | Spring 기본 기능 | session store·운영 경계 추가, 기존 JWT 전면 교체 | 제외 |
| 계속 bearer/localStorage | 변경 작음 | query/localStorage token 노출을 남김 | 제외 |

| 데모 identity 대안 | 장점 | 단점·위험 | 선택 |
|---|---|---|---|
| 방문자별 DB User, 24시간 cookie | 기존 owner isolation·history FK 재사용, 깨끗한 첫 화면 | cleanup 필요 | **선택** |
| 공유 demo User | 구현 작음 | 방문자 데이터 혼합과 IDOR 체감 위험 | 제외 |
| Google demo 계정 | 실제 Calendar 가능 | Google 보안 정책·2FA·lockout로 공개 불가 | 제외 |

### 10. 상세 설계

#### 10.1 인증·OAuth·CSRF

[목표 상태]

1. `/api/oauth/google/authorize`는 256비트 이상 random state를 만들고 서버 map에 생성 시각을 저장한다. 응답에는 Google URL과 `OAUTH_STATE` HostOnly HttpOnly cookie를 함께 보낸다. cookie 속성은 prod `Secure; SameSite=Lax; Path=/api/oauth/google/callback`, local `SameSite=Lax; Path=/api/oauth/google/callback`로 고정한다.
2. state map은 10분 TTL과 1,000개 상한을 가진다. authorize 때 만료 항목을 제거한 뒤에도 가득 차면 새 OAuth 시작을 503으로 거절한다. 임의의 유효 state를 축출하지 않는다.
3. callback은 query state와 cookie를 constant-time 비교한 뒤 server state를 원자적으로 제거한다. mismatch·만료·재사용은 모두 실패하고 state cookie는 성공·실패 모두 삭제한다. 단일 backend 전제이며 다중 replica로 바뀔 때만 Redis store로 올린다.
4. 성공 callback은 JWT를 `TASKFLOW_SESSION` HostOnly HttpOnly Secure SameSite=Lax Path=/ cookie로 설정하고 `/oauth/callback`로 token 없는 redirect를 보낸다. 오류 URL에는 내부 예외문 대신 고정 오류 code만 넣는다. state/session/logout cookie 삭제 응답은 생성 때와 같은 Path·SameSite·Secure 속성을 사용한다.
5. `JwtAuthenticationFilter`는 session cookie만 읽는다. 브라우저 bearer/localStorage 호환 경로는 남기지 않아 인증 source를 하나로 만든다.
6. Spring Security CSRF를 켜고 `XSRF-TOKEN` cookie/`X-XSRF-TOKEN` header 규약을 사용한다. 익명 `GET /api/auth/session`은 CSRF token을 실제로 materialize해 JS가 읽을 수 있는 cookie와 `{authenticated,userType,expiresAt}`을 반환하며 JWT 원문과 user email/name은 반환하지 않는다.
7. 프론트 Axios는 same-origin cookie와 XSRF header를 사용한다. 401이면 메모리 상태를 비우고 login으로 이동한다. logout은 CSRF가 필요한 POST이며 server가 session cookie를 삭제한다.
8. 공개 경로는 `GET /api/auth/session`, `POST /api/auth/demo`, Google authorize/callback으로만 좁힌다. `POST /api/auth/demo`도 CSRF 검증을 통과해야 하고, 그 밖의 미인증 API는 401이다.
9. application의 profile 기본값에서 `local`을 제거한다. local test API는 개발자가 profile을 명시한 경우에만 생성되고 prod Compose는 `SPRING_PROFILES_ACTIVE=prod`를 상수로 고정한다. `/api/test/**`는 Nginx에서도 404로 차단한다.

#### 10.2 방문자별 임시 사용자와 삭제

[목표 상태]

1. 사용자가 `데모로 둘러보기`를 누르면 CSRF 보호된 `POST /api/auth/demo`가 UUID 기반 내부 email과 표시명 `방문자`인 `Provider.DEMO` User를 하나 만든다. 사전 생성 project/task는 없다. User에는 고정 `expires_at=created_at+24h`를 저장한다.
2. DEMO JWT `exp`와 cookie Max-Age는 저장된 `expires_at`에서 계산한다. 같은 browser는 cookie가 유효한 동안 `/api/auth/session`으로 같은 User를 재사용하며 refresh는 없다. 인증 filter는 서명뿐 아니라 User 존재, provider와 `expires_at > now`를 확인한다.
3. `projects.owner_user_id`에는 User FK를 추가한다. 멤버십 모델이 없는 현재 범위에서 Task assignee는 인증 사용자 본인 또는 null만 허용하고, 다른 DEMO·Google User ID는 존재 여부와 이름을 노출하지 않는 동일한 404/검증 오류로 처리한다.
4. cleanup worker는 5분마다 만료 후 1분 grace가 지난 DEMO User ID를 최대 100개 찾되 사용자 한 명씩 별도 transaction으로 정리한다. `PROCESSING` Outbox가 있으면 해당 User만 다음 주기로 미룬다. 삭제 순서는 `calendar_outbox → task_history → tasks → projects → oauth_google_tokens(방어적) → users`다. embedding은 Task FK cascade로 지워진다.
5. session 쓰기 차단은 정확히 24시간이고 물리 삭제 목표는 만료 후 6분 이내다. 1분 grace는 만료 직전 시작된 요청과 cleanup의 경쟁을 피하기 위한 것이다. app 중단 중 누락된 row는 재기동 후 같은 조건으로 처리한다.
6. cleanup은 Google User를 query 조건에서 제외한다. 한 User의 실패는 그 transaction만 rollback하고 다음 User를 계속 처리한다. user 식별값을 로그·metric label로 내보내지 않는다.
7. DEMO resource 상한은 사용자당 project 10개, 활성·삭제 포함 Task 100개, 전체 mutating operation 500회다. User row의 `demo_mutation_count`를 같은 transaction에서 조건부 증가시켜 Redis 장애와 무관하게 history·Outbox 증폭도 제한한다. project name 100자, Task title 200자, description 4,000자, 검색 query 500자, HTTP body 64KiB를 server/Nginx 양쪽에서 제한한다. Nginx는 demo 생성뿐 아니라 모든 mutating `/api` 요청에 IP rate limit을 적용한다.

#### 10.3 데모 AI와 worker 관측

[목표 상태]

- DEMO 사용자는 live Gemini와 embedding HTTP client에 도달하지 않는다. 검색 service는 intent parser와 query embedding 전에 provider를 확인해 기존 lexical/fallback 경로로 끝낸다. summary·recommendation service도 cache/generator 진입 전에 현재 Task 데이터의 짧은 deterministic 결과와 `DEMO_LOCAL` 상태를 반환한다. AFTER_COMMIT embedding listener는 SecurityContext에 의존하지 않고 task→project owner→User provider를 직접 조회해 DEMO document embedding을 건너뛴다. Google User의 기존 live·cache 경로는 유지한다.
- DEMO는 Redis cache를 읽거나 쓰지 않는다. 생성·수정·검색·요약·추천 각각에서 Gemini intent/generator, document/query embedding HTTP와 cache service `find/save` 호출이 0회임을 검증한다. 빈 격리 Redis E2E에서는 DEMO 시나리오 전후 `DBSIZE`와 GET/SET operation counter가 변하지 않아야 하며 value는 로그·증거에 출력하지 않는다.
- Task를 동기화 활성으로 만들면 기존 transaction/outbox 로직이 `PENDING`을 만든다. 실제 scheduler가 선점한 뒤 Google token 부재를 확인해 `SKIPPED(no_google_link)`로 끝낸다.
- 공개 API의 `/trigger-worker`와 프론트 `지금 처리` 버튼은 삭제한다. scheduler 설정을 우회하는 별도 실행 경로를 만들지 않는다.
- Outbox UI는 `SKIPPED` 필터를 제공하고 “Google 연결이 없는 데모라 외부 호출 없이 건너뜀”으로 설명한다. `PENDING/PROCESSING` 동안만 polling하는 기존 정책은 유지한다.

#### 10.4 로그와 개인정보

[목표 상태]

- prod는 SQL·binder와 app DEBUG를 끈다.
- OAuth state, JWT, Google code/token, email, name, 검색 원문, Task title/description, upstream response body를 INFO/WARN/ERROR에 기록하지 않는다.
- 운영 로그는 request correlation ID, operation, outcome, latency, status/error code와 내부 record ID만 사용한다. record ID도 Discord·metric label에는 넣지 않는다.
- OAuth 실패는 stack trace가 필요한 server error와 정상적인 사용자 거절을 구분하고 browser에는 고정 오류 code만 제공한다.
- Nginx access log는 query string을 제외한 `$uri`만 기록하고 OAuth callback은 access log를 끈다. Cloudflare Logpush를 사용하지 않으며, 도입할 때 query·cookie redaction과 보존기간을 별도 검토한다.
- 일반 logout은 TaskFlow session cookie만 지우고 Google refresh token은 유지한다. 별도 CSRF 보호 `POST /api/oauth/google/disconnect`는 Google revoke를 시도한 뒤 local token row를 반드시 삭제하고 session을 종료한다. Google 계정 데이터 전체 삭제·보존 정책은 Production 심사 전에 별도 승인 과업으로 완료한다.

#### 10.5 Docker Desktop 배포 경계

[목표 상태]

배포 Compose project 이름과 volume은 개발 Compose와 분리한다. 서비스는 다음과 같다.

```text
Cloudflare Tunnel(host)
        │ http://127.0.0.1:${TASKFLOW_HTTP_PORT}
        ▼
Nginx(loopback publish) ── /api ──▶ backend(no host port)
        │                              │
        └─ React static                ├─ PostgreSQL/pgvector(no host port)
                                       └─ Redis(memory-only, no host port/volume)

Prometheus(no host port) ──scrape──▶ backend management port
Grafana(127.0.0.1 only) ───────────▶ Prometheus ──alert──▶ Discord
```

- 배포 Compose project 이름은 `taskflow-public`, persistent volume은 `taskflow-public-postgres-data`, `taskflow-public-prometheus-data`로 고정하고 `container_name`은 사용하지 않는다. Grafana는 dashboard·datasource·alert를 파일로 provisioning하고 `/var/lib/grafana`를 64MiB `tmpfs`로 둔다. Redis `/data`도 16MiB `tmpfs`로 명시해 image의 `VOLUME` 선언이 anonymous volume을 만들지 못하게 한다.
- network는 `edge`(Nginx↔backend), `data` internal(backend/migrator↔PostgreSQL), `cache` internal(backend↔Redis), `monitoring` internal(backend↔Prometheus↔Grafana), `grafana-egress`(Grafana의 Discord 발송)로 분리한다. `cache`에는 backend와 Redis만 연결하고 Nginx·DB·migrator·Prometheus·Grafana를 연결하지 않는다.
- Nginx만 `127.0.0.1`에 publish한다. Cloudflare Access OTP는 공개 demo 앞에 두지 않는다.
- Grafana는 별도 loopback port만 publish하고 Tunnel route를 만들지 않는다. Prometheus·backend·DB·Redis는 host port가 없다.
- Grafana anonymous access와 기본 admin password를 금지하고 required admin secret을 사용한다. Redis는 backend만 붙는 internal `cache` network로 격리하며 별도 password를 두지 않는다. backend가 이미 cache value 전체 권한을 가지므로 같은 secret을 추가하는 것은 해당 침해 경계를 줄이지 못한다.
- Redis는 `save ""`, `appendonly no`, `protected-mode no`, `maxmemory 128mb`, `maxmemory-policy allkeys-lru`, container `mem_limit: 192m`으로 고정한다. protected mode 해제는 host port가 없고 backend만 붙는 internal `cache` network에서만 허용한다. `WEEKLY_SUMMARY_CACHE_ENABLED=true`, `REDIS_URL=redis://redis:6379`는 prod Compose 상수이며 secret이 아니다.
- 배포 Compose에는 secret 기본값을 두지 않고 `${NAME:?required}`로 실패시킨다. 현재 개발 `docker-compose.yml`과 local PostgreSQL publish·credential 보강은 이번 배포 Redis 변경에서 제외하고 기존 보안 finding의 별도 승인 과업으로 유지한다.
- prod 주간 요약 TTL은 기존 설정 변수로 900초에 고정하고 추천 TTL은 기존 90초를 유지한다. Task 변경·삭제 뒤 정상 경로는 fingerprint가 달라져 과거 exact cache를 반환하지 않는다. Gemini fallback-eligible 실패 때만 `STALE_FALLBACK`이 최대 15분 내 삭제된 Task 내용을 같은 project owner에게 보여줄 수 있으며 UI에 상태를 표시한다. project 삭제는 owner-scoped project 조회가 먼저 실패하므로 cache를 반환하지 않는다. Google disconnect는 TaskFlow project 삭제가 아니며 향후 계정 전체 삭제 과업에는 project별 cache purge 또는 Redis 전체 recreate 검증을 포함한다.
- backend와 frontend는 multi-stage build를 사용하고 runtime은 non-root로 실행한다. base image와 PostgreSQL/Redis/Prometheus/Grafana image는 구현 시점의 digest로 고정한다. frontend는 `npm ci`, backend는 Gradle wrapper를 사용하고 wrapper distribution checksum과 dependency lock을 저장한다.
- 배포 image는 Git SHA tag를 사용하며 `latest`를 사용하지 않는다. 첫 배포는 local Docker Desktop build이고 registry push는 별도 범위다. final SHA, tag, image ID와 이전 1개 release image를 기록·보존한다.
- 장기 실행 service는 `restart: unless-stopped`, 일회성 migrator는 재시작하지 않는다. Docker JSON log는 service당 `max-size: 10m`, `max-file: 3`, Prometheus는 7일 또는 1GiB 중 먼저 도달하는 보존 한도를 사용한다.

#### 10.6 PostgreSQL/pgvector 권한과 schema

[목표 상태]

- fresh deployment volume만 사용한다. init script는 bootstrap superuser로 `vector` extension과 `taskflow_owner`, `taskflow_app` 역할을 만든다.
- owner credential은 일회성 Flyway container에만 제공한다. migrator는 전체 JPA schema, vector table/index, owner/default privileges와 runtime grant를 versioned migration으로 적용한 뒤 종료한다. backend container에는 `taskflow_app` credential만 제공한다.
- prod JPA는 `validate`다. migration 성공 전 backend readiness는 성공하지 않는다.
- app role은 application schema의 table DML, sequence 사용, connect·schema usage만 가진다. public schema의 불필요한 create 권한을 회수한다.
- `TaskSearchEmbeddingStore`의 runtime extension/table/index 생성과 dimension mismatch drop은 prod에서 금지하고 migration 결과를 검증만 한다. local 자동 schema 동작은 명시적 local profile에서만 허용한다.
- credential 원문은 Git에서 제외한 `.env.production`에 두고 파일 권한을 제한한다. 환경변수가 local Docker 관리자에게 보일 수 있다는 단일 host 잔여 위험은 기록한다. Docker Swarm/Kubernetes secret store는 도입하지 않는다.

#### 10.7 readiness·resilience·rollback

[목표 상태]

- PostgreSQL은 backend readiness의 필수 의존성이고 Redis는 별도 degraded health다. backend는 Redis가 없거나 연결이 끊겨도 기동하며 Nginx는 정적 health endpoint를 제공한다. Compose `depends_on: condition: service_healthy`는 DB/migration 시작 순서만 보조하고 backend 시작을 Redis health에 묶지 않는다.
- Redis cache read/write 예외는 cache miss·저장 생략으로 fail-open 처리한다. 같은 backend process에서 Redis stop→core 기능 유지→start→cache read/write 복구를 실제로 검증한다. DEMO local AI는 Redis를 호출하지 않는다.
- active Redis read/write health probe를 readiness나 Prometheus scrape에서 호출하지 않는다. 실제 cache operation의 success/error counter만 degraded 신호로 쓰며 쓰기 health check는 수동 Gate 6 검증으로 제한한다.
- app과 worker는 동일 backend process 하나에서 돈다. replica를 늘리지 않는다. worker lease/claim은 기존 원자적 선점을 유지한다.
- rollback은 기록한 이전 Git SHA image tag/image ID와 이전 Compose/config로 되돌린다. forward-only DB migration은 파괴적 down migration 대신 백업 복원 또는 호환되는 후속 migration을 사용한다.
- DB backup은 실행 중 raw volume 복사가 아니라 `pg_dump -Fc`와 `pg_dumpall --globals-only`를 사용한다. fresh initial deployment에는 복원할 사용자 데이터가 없음을 기록하고, 이후 migration은 암호화된 backup과 새 volume restore 검증 뒤 진행한다.

#### 10.8 관측과 Discord

[목표 상태]

Actuator는 내부 management port에서 `health`, `prometheus`만 노출한다. Nginx는 `/actuator`를 proxy하지 않는다. 사용자·project·task·query를 metric label에 넣지 않는다.

최소 metric은 다음으로 제한한다.

- `demo_sessions_started_total`
- `demo_users_expired_total`
- `demo_cleanup_failures_total`
- `demo_oldest_expired_age_seconds`
- `demo_tasks_created_total`
- `outbox_processed_total{outcome="success|failed|skipped",reason="none|no_google_link"}`
- `outbox_oldest_processable_age_seconds`
- 기존 Gemini 호출의 기능·outcome별 count/latency와 cache outcome. 모델명 외 사용자 입력은 label로 쓰지 않는다.

Grafana 기본 alert는 다음과 같다.

| alert | 조건 | 목적 |
|---|---|---|
| demo usage | `sum(increase(demo_sessions_started_total[1h])) >= 3 and sum(increase(outbox_processed_total{outcome="skipped",reason="no_google_link"}[1h])) >= 5` | 실제 방문·worker 시연 알림 |
| abuse | `sum(increase(demo_sessions_started_total[10m])) >= 20 or sum(increase(outbox_processed_total{outcome="skipped",reason="no_google_link"}[10m])) >= 50` | 자동화·과도 사용 조기 감지 |
| worker backlog | `max(outbox_oldest_processable_age_seconds) > 120`, `for: 2m` | worker repository와 같은 조건의 PENDING, retry 시각이 지난 FAILED, lease 만료 PROCESSING 중 가장 오래된 항목이 두 주기를 넘김 |
| backend down | `up{job="taskflow"} == 0` 또는 no-data, `for: 2m` | backend·scrape 중단 탐지 |
| cleanup failure | `sum(increase(demo_cleanup_failures_total[15m])) > 0` | 만료 데이터 삭제 실패 탐지 |
| cleanup overdue | `max(demo_oldest_expired_age_seconds) > 600`, `for: 5m` | 삭제 대상이 10분 이상 남는 상태 탐지 |

빈 backlog와 만료 대상이 없을 때 gauge는 `0`을 계속 내보낸다. counter alert는 no-data를 정상으로 보지 않고 backend down 규칙으로 연결한다. alert는 `group_by: [alertname, environment]`, `group_wait: 30s`, `repeat_interval: 4h`로 묶고 resolved 알림을 보낸다. synthetic counter reset·no-data·threshold 경계로 rule을 검증한다.

Discord contact point에는 alert 이름, 집계값, 환경, dashboard link만 보내며 webhook URL과 식별자는 포함하지 않는다. Nginx는 demo session·OAuth authorize·AI endpoint와 mutating API에 IP 기반 `limit_req`를 적용하되 Cloudflare header를 신뢰할 수 있는 loopback Tunnel 경계에서만 사용한다. Prometheus·Grafana·host가 함께 중단되면 내부 alert도 발송되지 않는 것은 남은 위험이며, 외부 uptime monitor는 실제 필요가 확인될 때 별도 승인으로 추가한다.

### 11. 위험과 실패 시나리오

[가정]

| 실패 | 영향 | 탐지 | 예방·복구 |
|---|---|---|---|
| OAuth state cookie 누락·재사용 | login 실패 또는 login CSRF | 4xx count와 고정 오류 code | browser binding, one-time removal, TTL·상한 |
| CSRF token bootstrap 누락 | 모든 write 403 | frontend E2E | session endpoint에서 token 발급 후 write |
| cleanup 순서/FK 오류 | 임시 데이터 누적 | cleanup failure counter·DB count | set-based transaction, FK 순서 테스트 |
| demo 분기 누락 | Gemini 비용 발생 | mock call test·Gemini metric | signed identity에서 server-side deny, endpoint별 회귀 테스트 |
| worker 정지 | PENDING 적체 | oldest pending alert | health/runbook, container restart, lease recovery test |
| DB role 과권한 | app 침해 시 schema/role 변경 | privilege query | owner/runtime 분리와 grants 검증 |
| Discord webhook 노출 | 임의 메시지 발송 | Git secret scan·Discord audit | env-only, 로그 금지, 노출 시 rotate |
| Docker Desktop/host 재부팅 후 미기동 | 공개 서비스 중단 | Cloudflare 5xx·health | restart policy와 실제 reboot verification |
| migration 후 old image 비호환 | rollback 실패 | rollback rehearsal | additive migration 우선, 배포 전 backup, 호환 window |

### 12. readiness와 resilience

[목표 상태]

| 구분 | 준비·복구 방식 | 검증 |
|---|---|---|
| readiness | required secret → migration → DB health → app readiness → Nginx route. Redis는 필수 조건이 아니라 degraded health | Redis를 시작하지 않은 fresh cold start와 필수 secret 누락 실패 확인 |
| resilience | worker lease 회수, DB/Redis reconnect, app/container restart, cleanup catch-up | backend container ID를 유지한 Redis stop/start와 상태·데이터 확인 |

### 13. 완료 조건과 검증 계획

[검증 예정]

#### 1단계

| 명령 | 기대 결과·실패 판정 |
|---|---|
| `./gradlew test --tests '*OAuthState*' --tests '*Security*' --tests '*Demo*' --tests '*Cleanup*' --tests '*WeeklySummary*' --tests '*Recommendation*'` | mismatch/expiry/reuse/capacity, cookie/CSRF, assignee 격리, 24시간 경계·cleanup, 모든 DEMO AI/cache client 0회, Task 변경·삭제 exact miss, fallback-eligible 실패만 `STALE_FALLBACK`, project 삭제 시 cache 미조회 assertion 통과 |
| `./gradlew test` | exit 0. 관련 task의 `NO-SOURCE`는 coverage로 보지 않음 |
| `npm --prefix frontend run lint` | exit 0 |
| `npm --prefix frontend run build` | exit 0 |
| `npm --prefix frontend run test:e2e -- --project=chromium` | 최소 Playwright spec 하나가 새 browser→CSRF→demo, 같은 context 재사용, 두 context IDOR 404, logout, `PENDING→SKIPPED`를 검증 |
| `rg -n 'localStorage|Authorization.*Bearer|[?&]token=' frontend/src src/main/java` | 인증 구현에서 일치 0건. 테스트 fixture·문서 일치는 별도 판독 |

Playwright는 frontend 인증·격리를 재현하는 단 하나의 E2E dependency로만 추가하며 component test framework는 새로 만들지 않는다. 고정 clock으로 만료 직전·정확한 경계·직후 요청과 cleanup 동시성을 검증한다.

#### 2단계

| 명령·절차 | 기대 결과·실패 판정 |
|---|---|
| `docker compose -p taskflow-public --env-file .env.production -f compose.production.yml config --quiet` | exit 0. 이는 문법·변수 해석만 증명 |
| `docker compose -p taskflow-public --env-file .env.production -f compose.production.yml config --format json`을 `deploy/verify-compose-boundary.sh`에 전달 | project·volume 이름, publish address, network membership, Redis/Grafana `tmpfs`, backend의 exact `WEEKLY_SUMMARY_CACHE_ENABLED=true`·`WEEKLY_SUMMARY_CACHE_TTL_SECONDS=900`·`REDIS_URL=redis://redis:6379` assertion 통과. Redis URL interpolation·userinfo·TLS·외부 hostname은 실패 |
| `docker compose -p taskflow-public --env-file .env.production -f compose.production.yml build --pull` | 모든 local app image build 성공, base/service digest와 final image ID 기록 |
| `docker compose -p taskflow-public --env-file .env.production -f compose.production.yml up -d --wait` | fresh `taskflow-public-postgres-data`에서 migrator exit 0, 필수 service healthy |
| `docker compose -p taskflow-public --env-file .env.production -f compose.production.yml --profile verify run --rm db-verify` | app credential의 DML 성공, `CREATE TABLE/ROLE/EXTENSION` 실패를 `verify-runtime-privileges.sh`가 assertion |
| `docker compose -p taskflow-public --env-file .env.production -f compose.production.yml ps --format json`과 각 service의 `docker inspect` | Nginx·Grafana만 loopback publish, backend/DB/Redis/Prometheus host port 0개, volume·network membership과 Redis persistence/memory limit이 §10.5와 일치 |
| `docker compose -p taskflow-public --env-file .env.production -f compose.production.yml exec -T prometheus promtool check config /etc/prometheus/prometheus.yml` | exit 0. Prometheus 설정 유효성만 증명 |
| `docker compose -p taskflow-public --env-file .env.production -f compose.production.yml --profile verify run --rm http-verify http://prometheus:9090/api/v1/query?query=up%7Bjob%3D%22taskflow%22%7D` | live query의 값 `1`. 실제 scrape 성공을 증명 |
| `docker compose -p taskflow-public --env-file .env.production -f compose.production.yml --profile verify run --rm http-verify http://backend:9091/actuator/health/readiness` | backend readiness `UP` |
| `curl --fail http://127.0.0.1:${TASKFLOW_HTTP_PORT}/healthz` | Nginx 정적 health만 성공. `/actuator`, `/api/test`는 public route에서 404 |
| `deploy/verify-redis-resilience.sh` | Redis container가 없는 cold start에서 backend readiness `UP`, backend ID 불변 상태의 Redis start→stop→start 동안 core 기능 유지, cache error 후 read/write·cache hit 복구, DEMO 전후 `DBSIZE`·GET/SET counter 불변 |
| backend stop 2분, synthetic counter/reset/no-data와 worker backlog 생성·해소 | exact PromQL의 firing/resolved, group/repeat 억제, Discord 수신 확인 |

DB·backend·Redis restart와 restore test는 현재 개발 container·volume ID와 local `.env` checksum을 전후에 기록해 불변임을 확인한다. 배포 검증은 기존 Upstash `context DB`에 연결하지 않는다. raw volume copy는 사용하지 않는다.

`deploy/verify-redis-resilience.sh`는 동일한 `-p taskflow-public --env-file .env.production -f compose.production.yml` 인자를 사용해 다음 순서를 고정한다.

1. `up -d postgres`와 `run --rm migrator` 이후 `up -d backend nginx prometheus grafana`만 실행한다.
2. `ps -q redis`가 비어 있고 backend readiness가 `UP`인지 확인한 뒤 backend container ID를 기록한다.
3. `up -d redis` 직후 `DBSIZE=0`을 확인하고 `INFO commandstats`의 GET/SET baseline을 기록한다. Google cache를 만들기 전에 DEMO summary·recommendation을 실행한 뒤 `DBSIZE=0`과 GET/SET delta 0을 확인한다.
4. 인증된 Google cache health와 동일 요약·추천의 cache hit를 확인한다. key value는 출력하지 않고 Redis `TTL`로 주간 요약 900초 이하·추천 90초 이하의 양수 만료만 확인한다.
5. `stop redis` 후 CRUD·Outbox·DEMO local AI가 성공하고 cache error counter가 증가하는지 확인한다.
6. `start redis` 후 같은 backend ID에서 cache read/write와 cache hit가 복구되는지 확인한다.
7. image·container inspect에서 PostgreSQL·Prometheus 외 `Type=volume` mount가 0건이고 Redis `/data`·Grafana `/var/lib/grafana`가 `tmpfs`인지 확인한다. Redis recreate 뒤 `dump.rdb`·AOF가 없고 Grafana의 임시 SQLite state가 유지되지 않는 것도 확인한다.

#### 3단계

| 명령·수동 절차 | 기대 결과·실패 판정 |
|---|---|
| `BASE_URL=${PUBLIC_ORIGIN} npm --prefix frontend run test:e2e -- --project=chromium` | public HTTPS에서 header/cookie/CSRF, 두 browser 격리, 같은 session 재사용, `PENDING→SKIPPED` 성공 |
| `curl -sS -D - ${PUBLIC_ORIGIN}/api/auth/session`과 unsafe request | Secure/HttpOnly/SameSite/Path와 CSRF 403/정상 성공 확인, URL·로그에 JWT/code/state 없음 |
| 등록된 Google test user의 OAuth→Task sync→Google Calendar 확인→테스트 event 삭제 | 실제 callback·create/update/delete 성공. demo `SKIPPED` 증거와 분리 |
| `${PUBLIC_ORIGIN}/actuator`, Grafana, Prometheus, DB 후보 route 요청 | 모두 외부 접근 불가. Cloudflare에는 Nginx 공개 hostname 하나만 존재 |
| 이전 `${GIT_SHA}` image tag로 Compose 변경→`up -d --wait`→현재 tag 복귀 | app rollback과 재복귀 성공, DB row/schema 유지 |
| Mac mini reboot 후 Docker Desktop·host cloudflared·Compose 확인 | 자동 기동과 public health 복구. 실패하면 상시 배포 완료로 주장하지 않음 |

`.codex-evidence/`를 Git ignore하고 권한을 700으로 만든 뒤, 모든 명령의 시각·대상 SHA·exit code·secret을 제외한 assertion 요약만 `.codex-evidence/<SHA>/`에 저장한다. raw HTTP header, cookie, OAuth query, environment dump는 저장하지 않는다. `git status --short` 무변경과 token/cookie/secret pattern scan 0건을 Gate 6에 포함하고 최종 요약은 이 문서의 Gate 6 표에 남긴다. Google·Cloudflare·Discord 외부 변경과 테스트 event 정리는 각각 승인·실행·readback을 기록한다.

환경별 증거는 분리한다.

| 환경 | 범위 | 증명 경계 |
|---|---|---|
| local | 코드·단위·통합·Docker Desktop 전체 | CI와 공개 HTTPS를 증명하지 않음 |
| CI | PR에서 백엔드 전체 테스트와 프론트 lint·build·Playwright 목록 검증 | 실제 브라우저 E2E와 공개 배포를 증명하지 않음 |
| deployed | Cloudflare HTTPS·Google callback·Discord·재시작·rollback | 검증한 배포 SHA만 증명 |

### 14. 롤백·복구

[목표 상태]

| 실패 지점 | 중단 조건 | 롤백·복구 |
|---|---|---|
| 1단계 auth | login/write/E2E 격리 실패 | 구현 commit revert, 기존 bearer 방식으로 공개 배포하지 않고 로컬만 복원 |
| 2단계 cold start | migration·health·privilege 실패 | public route 연결 금지, fresh deployment volume 폐기 여부를 별도 승인받고 config 수정 |
| 3단계 public | 인증 우회·데이터 노출·secret 노출 | Cloudflare route 우선 차단, credential rotate, 이전 image 또는 백업 복원 |
| alert 오작동 | 반복 spam 또는 미탐 | Grafana rule disable, webhook 유지/rotate 판단 후 threshold 보정 |

현재 local DB volume, OAuth token, stash, main worktree는 rollback 대상으로 조작하지 않는다. 배포 volume 삭제는 파괴적 cleanup이므로 정확한 대상과 백업을 제시하고 별도 승인을 받는다.

### 15. 사용자 결정 항목

[결정]

이미 확정된 항목은 Docker Desktop, 현재 Mac mini, 방문자별 24시간 DEMO User, 실제 worker `SKIPPED`, Discord webhook, 배포 전용 memory-only Redis, 3단계 구현과 리뷰 체계다. 현재 local Upstash는 이번 범위에서 변경하지 않는다.

[결정 필요]

| 결정 | 시점 | 기본 처리 |
|---|---|---|
| 공개 hostname과 Google redirect URI | 3단계 시작 전 | `${PUBLIC_ORIGIN}`으로 설계하고 외부 변경 직전에 사용자 확인 |
| Google OAuth Production 심사 착수 | 별도 후속 과업 | Testing 등록 사용자만 실제 Calendar 경로 사용, 공개 demo는 영향 없음 |
| Google 사용자 계정 데이터 전체 삭제·보존 정책 | Production 심사 전 | 이번 범위는 token disconnect까지만 구현, 심사 전 별도 개인정보 과업으로 승인 |
| host 전체 중단을 보는 외부 uptime monitor | 내부 관측 검증 후 | 현재는 잔여 위험으로 공개, 필요가 확인될 때만 추가 |
| 배포 volume·branch·stash cleanup | 검증·보존기간 이후 | 기본 보존, 정확한 대상별 별도 승인 |

### 16. 근거

[현재 사실]

- 저장소: `SecurityConfig`, `JwtAuthenticationFilter`, `GoogleOAuthController`, `OAuthStateStore`, `User`, `Provider`, `TaskService`, `CalendarOutboxWorker`, `OutboxController`, `OutboxPage`, `application.yml`, `docker-compose.yml`, `build.gradle`.
- Git: 기준 `2bf04bfb4b24093b44931630b89c312e98c164fb`, main/origin 동일, 설계 전 clean, stash 3개 보존.
- Wiki: `2026-08-12-taskflow-deployment-readiness.md`, `2026-08-12-taskflow-auth-design-decision.md`, `2026-08-18-taskflow-observability-decision.md`, `google-oauth-verification-policy.md`.
- Codex Security Standard scan: `38798d8b-ad17-420b-9215-ba3e1720ee08`. scanner finding은 위 코드 흐름으로 재검증한 항목만 설계에 반영했다.

## 위험도 판정

| 축 | 점수 | 근거 |
|---|---:|---|
| 영향 범위 | 2 | backend·frontend·DB·Docker·네트워크·외부 OAuth·관측 전체 |
| 실패 영향 | 2 | 인증 우회, 사용자 간 데이터 노출, secret·개인정보 노출 가능 |
| 되돌리기 | 1 | image/config는 revert 가능하지만 DB migration·외부 route·credential 복구 필요 |
| 검증 불확실성 | 2 | 공개 HTTPS, Google, Cloudflare, Discord, host reboot는 deployed 검증 필요 |
| **합계** | **7** | 기본 높음 |

- 강제 상향: 보안 침해와 데이터 삭제 가능성.
- 전문 owner: 보안, 개인정보·데이터, 인프라·운영, 관측성.

## 리뷰 운영

[결정]

- Gate 2: 보안·데이터 reviewer와 인프라·운영 reviewer 2명이 서로의 의견을 보지 않고 이 문서를 읽기 전용 검토한다.
- 각 구현 단계 종료: 해당 단계 전문 reviewer 1명이 중간 checkpoint를 수행한다. finding은 `즉시 해결`, `추후 과업`, `근거 부족 기각`, `사용자 결정`으로 판정한다.
- 최종 후보 diff가 Gate 6 동안 고정되고 미해결 Blocker/High가 없을 때만 Gate 5·7을 결합한다. 단계별 reviewer 1명과 공개 직전 추가 reviewer 1명이 최종 diff와 실행 증거를 독립 검토한다.
- 검증 중 코드·데이터·계약·보안 상태가 바뀌면 결합을 취소하고 Gate 5 → Gate 6 → Gate 7을 분리한다.
- reviewer는 shared worktree를 수정하지 않는다. Blocker/High 원문과 주 작업자 종합은 이 문서의 리뷰 ledger에 보존한다.

### Gate 2 독립 리뷰 ledger

#### Reviewer A — 보안·데이터·개인정보

**A-1**

- 영역: 배포 profile·직접 Google 쓰기 경로
- 심각도: **Blocker**
- 발견 내용: 기본 `local` profile에서 임의 `userId`로 Google Calendar를 직접 쓰는 test API가 남는다.
- 근거: `application.yml:5-7`, `CalendarTestController.java:15-35`, 설계 §10.3·§10.5
- 발생 가능한 영향: 실제 Google Calendar 무단 변경과 사용자 간 권한 침해.
- 권장 조치: test controller 삭제 또는 명시적 local-only 격리, prod profile 고정.
- 검증 방법: prod·profile 미지정에서 bean 부재와 `/api/test/calendar/create` 404, Google client 0회.
- 확신도: 높음
- 주 작업자 종합: **반영**. profile 기본 local 제거, prod Compose 고정, Nginx 차단과 회귀 검증을 §10.1·§13에 추가했다.

**A-2**

- 영역: CSRF bootstrap·OAuth cookie
- 심각도: **High**
- 발견 내용: 익명 DEMO POST 전에 CSRF token을 받을 공개 경로와 cookie Secure·Path·삭제 속성이 불완전하다.
- 근거: `SecurityConfig.java:37-40`, 설계 §10.1·§10.2
- 발생 가능한 영향: demo 진입 401/403 또는 CSRF 예외 처리로 보호 약화.
- 권장 조치: session/demo/OAuth 공개 경로를 정확히 지정하고 session GET에서 token materialize, cookie 속성 고정.
- 검증 방법: 새 browser session GET→demo POST, token 없음 403, 나머지 API 401, cookie 속성 확인.
- 확신도: 높음
- 주 작업자 종합: **반영**. §10.1에 exact public matcher와 cookie contract를 추가했다.

**A-3**

- 영역: 사용자 격리·cleanup FK
- 심각도: **High**
- 발견 내용: global assignee ID가 다른 사용자 이름을 노출하고 cleanup FK 실패를 만들 수 있다.
- 근거: `TaskService.java:54-59,118-123`, `Task.java:37-39`, `TaskResponse.java:78-87`
- 발생 가능한 영향: user ID/name 열거와 cleanup rollback·보존기간 초과.
- 권장 조치: assignee를 current user 또는 null로 제한하고 사용자별 cleanup transaction을 격리.
- 검증 방법: 교차 assignee 거절·이름 비노출, 한 User 실패 중 다른 User cleanup 진행.
- 확신도: 높음
- 주 작업자 종합: **반영**. §10.2에 owner FK, assignee 경계, per-user transaction을 추가했다.

**A-4**

- 영역: JWT 만료·삭제 race
- 심각도: **High**
- 발견 내용: User `created_at`과 JWT 발급 시각이 다르고 filter가 User 존재·만료를 검증하지 않아 삭제 후 orphan write가 가능하다.
- 근거: `JwtAuthenticationFilter.java:32-48`, `JwtTokenProvider.java:28-37`, `Project.java:28-33`
- 발생 가능한 영향: 24시간 이후 쓰기, orphan project, 경계 시점 500.
- 권장 조치: 고정 `expiresAt`을 JWT와 cleanup의 공통 기준으로 사용하고 User/FK를 검증.
- 검증 방법: 고정 clock 경계·동시성·orphan 0건.
- 확신도: 높음
- 주 작업자 종합: **반영**. 저장된 `expires_at`, request-time User 검증, owner FK, 1분 in-flight grace를 §10.2에 추가했다.

**A-5**

- 영역: DEMO Gemini·embedding 차단
- 심각도: **High**
- 발견 내용: endpoint branch만으로는 search parser와 AFTER_COMMIT embedding 호출을 닫지 못한다.
- 근거: `ProjectTaskSearchService.java:144-186`, `TaskSearchEmbeddingSyncListener.java:14-21`, `TaskSearchEmbeddingService.java:93-137`
- 발생 가능한 영향: DEMO 데이터 외부 전송과 Gemini 비용.
- 권장 조치: parser/generator/cache 전 분기, embedding service가 owner provider를 자체 조회.
- 검증 방법: DEMO 생성·수정·검색·요약·추천의 모든 HTTP AI client 0회.
- 확신도: 높음
- 주 작업자 종합: **반영**. 동기·비동기 호출 경계를 §10.3과 §13에 명시했다.

**A-6**

- 영역: 공개 익명 사용량·데이터 증폭
- 심각도: **High**
- 발견 내용: 한 DEMO session이 CRUD·본문·Outbox를 무제한 늘릴 수 있고 alert는 예방이 아니다.
- 근거: `CreateTaskRequest.java:16-30`, 설계 §10.2·§10.8
- 발생 가능한 영향: DB·disk·worker·cleanup 고갈.
- 권장 조치: resource·본문 상한과 mutating API rate limit.
- 검증 방법: 상한 초과 4xx/429와 cleanup 독점 방지.
- 확신도: 높음
- 주 작업자 종합: **반영**. user별 project/task, DTO/body 크기와 Nginx mutation rate limit을 §10.2에 고정했다.

Reviewer A의 Medium·Low 3건도 반영했다. DEMO Redis key 0건과 Google disconnect/token revoke 경계를 추가했고, Nginx가 OAuth query를 access log에 남기지 않게 했으며, Wiki의 “Google 단일 로그인·공유 demo user”를 “영속 Google user + 방문자별 임시 DEMO”로 대체할 항목을 명시했다. Google 계정 데이터 전체 삭제 정책은 Production 심사 전 후속 과업이다.

#### Reviewer B — 인프라·운영·관측성

**B-1**

- 영역: PostgreSQL/Flyway 최소 권한
- 심각도: **High**
- 발견 내용: backend가 migration owner credential을 가지거나 runtime DDL을 계속하면 최소 권한이 성립하지 않는다.
- 근거: 기존 설계 §10.6, `TaskSearchEmbeddingStore.java:31-57,171-180`
- 발생 가능한 영향: backend 침해 시 schema 변경 또는 prod semantic store 비활성화.
- 권장 조치: 일회성 migrator에만 owner secret, backend app role만 사용, vector schema migration화.
- 검증 방법: fresh migration 뒤 DML 성공, CREATE TABLE/ROLE/EXTENSION 실패와 semantic 정상.
- 확신도: 높음
- 주 작업자 종합: **반영**. §10.6에서 migrator/runtime container와 runtime DDL을 분리했다.

**B-2**

- 영역: Redis readiness·장애 격리
- 심각도: **High**
- 발견 내용: “cache만 저하” 목표와 Redis 필수 readiness·현재 예외 전파가 모순이다.
- 근거: 기존 설계 §10.7, `RedisWeeklySummaryCacheService.java:21-44`
- 발생 가능한 영향: Redis 장애가 backend 미기동·AI endpoint 장애로 확대.
- 권장 조치: DB만 필수 readiness, Redis degraded health, cache fail-open.
- 검증 방법: Redis 없는 cold start와 stop/start 중 core 기능·자동 복구.
- 확신도: 높음
- 주 작업자 종합: **반영**. §10.7과 §13에 장애 경계와 검증을 추가했다.

**B-3**

- 영역: Gate 6 증거 재현성
- 심각도: **High**
- 발견 내용: frontend test script와 exact command·assertion·증거 위치가 없었다.
- 근거: 기존 설계 §13, `frontend/package.json:6-10`
- 발생 가능한 영향: 구현 후 검증 누락과 완료 주장 재현 불가.
- 권장 조치: 단계별 명령·exit code·assertion·E2E 도구·외부 정리 절차 고정.
- 검증 방법: 제3자가 final SHA에서 같은 matrix 재현.
- 확신도: 높음
- 주 작업자 종합: **반영**. 최소 Playwright E2E 하나와 단계별 exact command·판정·증거 위치를 §13에 추가했다.

Reviewer B의 Medium 4건도 반영했다. exact `increase()` PromQL·no-data·repeat 억제, backend/cleanup alert와 host 전체 장애의 잔여 위험, fixed Compose project/volume·network·Grafana/Redis secret, restart/log/Prometheus retention·image ID·`pg_dump` rollback을 §10.5~§13에 추가했다.

#### Gate 2 1차 종합

- 1차 판정: **보류**
- Blocker 1건, High 8건, Medium 6건, Low 1건을 원본 코드로 재확인했다.
- 처리: Blocker/High 전부 설계에 즉시 반영했다. Medium은 현재 공개·복구 경계에 필요한 부분을 즉시 반영했고, Google 계정 전체 삭제 정책과 외부 uptime monitor만 명확한 조건을 둔 추후 과업으로 남겼다. Low Wiki 불일치는 Gate 8 동기화 범위에 반영했다.
- 선택 재리뷰: 변경된 보안·데이터 owner와 인프라·운영 owner에게만 2차 review-only 검토를 요청한다. 다른 reviewer나 구현 reviewer는 반복하지 않는다.

#### 선택 재리뷰 결과

| reviewer | 변경 owner | 반복하지 않은 범위 | 결과 |
|---|---|---|---|
| 보안·데이터·개인정보 | A-1~A-6, DEMO cache, Google disconnect, Nginx log, Wiki identity | 다른 reviewer 결론과 구현 영역 | **통과 — 미해결 finding 없음** |
| 인프라·운영·관측성 2차 | B-1~B-3와 기존 Medium | 보안·데이터 owner | High 해소. Redis readiness 문구, live scrape/health 증명, processable metric, Compose project flag, evidence secret 경계 Medium 5건 수정 요청 |
| 인프라·운영·관측성 최종 | 위 Medium 5건과 새 위험만 | 이미 해소된 B-1~B-3 및 다른 owner | **통과 — 미해결 finding 없음** |

#### Gate 2 최종 판정

- 결과: **통과**
- 미해결 Blocker/High/Medium/Low: 없음.
- 증명 경계: 이 판정은 구현 전 설계의 일관성과 누락 여부만 검토했다. 코드 구현, test, Docker 실행, 외부 배포 성공을 증명하지 않는다.
- Gate 3: 높은 위험 변경이므로 사용자가 이 최종 설계와 남은 위험을 명시적으로 승인하기 전에는 구현하지 않는다.

#### Gate 2 변경분 재검토 — 외부 Upstash 전환안(후속 사용자 결정으로 폐기)

> 아래 외부 Upstash 목표와 1차 finding은 의사결정 이력을 보존한 것이다. 현재 목표 상태는 뒤의 “사용자 결정과 2차 수정 — 배포 전용 Docker Redis”가 대체한다.

[현재 사실]

- 2026-08-19 사용자 확인으로 운영 cache는 자체 Redis container가 아니라 현재 사용 중인 Upstash를 유지하기로 했다.
- Upstash Console과 secret을 제외한 local endpoint를 대조했고, 현재 Free Tier `context DB`는 TaskFlow와 무관한 key를 포함해 공개 배포용으로 공유하지 않는다.
- 이 변경은 인증·DEMO·PostgreSQL 설계를 바꾸지 않고 cache topology, secret, network egress, readiness·resilience와 Gate 6 절차만 바꾼다.

[목표 상태]

- Compose의 Redis service·volume·password·image·healthcheck를 삭제하고 기존 Lettuce/`REDIS_URL` 계약으로 TaskFlow 전용 Upstash DB에 TLS 연결한다.
- invalid external endpoint에서도 backend readiness와 core 기능을 유지하고, 정상 endpoint 복원 뒤 cache가 재연결되는 것을 검증한다.

[검증 예정]

- 변경 owner인 인프라·운영·관측성 reviewer 1명만 위 diff와 관련 검증을 독립 재검토한다. 이미 통과한 보안·데이터 owner는 반복하지 않는다.
- 재검토 finding은 이 절에 보존하고 미해결 Blocker/High 또는 새 사용자 결정이 있으면 Gate 3로 진행하지 않는다.

##### 인프라·운영·관측성 변경분 1차 재검토

- 판정: **보류**
- High — 외부 데이터 신뢰 경계: full response cache에 project·Task·Calendar·AI 생성 내용이 들어가지만 Free Tier의 disk·backup·region·삭제·at-rest encryption 경계가 승인 자료에 없었다. **처리: 사용자 결정 필요.**
- Medium — 장애·복구 증거: invalid endpoint로 backend를 recreate하는 절차는 cold-start fail-open과 재기동 복구만 증명하며 같은 process의 Lettuce 자동 재연결을 증명하지 않는다. `context DB` 불변 assertion도 필요하다. **처리: 반영 예정.**
- Medium — command budget: active health check는 1회에 `SET`·`GET`·`DEL` 3 commands라 15초 probe만으로 월 500,000 한도를 넘는다. 5초 cache timeout도 degraded latency에 과하다. **처리: 반영 예정.**
- 통과한 범위: Redis service·volume 제거, backend external TLS, required `REDIS_URL`, `rediss://` 전용, 비필수 readiness 방향.
- 다음 gate: 공개 배포 cache 정책을 사용자가 결정한 뒤 해당 대안으로 문서를 수정하고 변경된 인프라·운영 및 개인정보·데이터 owner만 선택 재리뷰한다.

##### 사용자 결정과 2차 수정 — 배포 전용 Docker Redis

[결정]

- 2026-08-19 사용자는 외부 Upstash 대신 공개 배포 Compose에 Redis를 직접 실행하는 방향을 선택했다.
- Redis는 배포 전용이다. 현재 개발 Compose, local `.env`, Upstash `context DB`는 변경하지 않는다.
- cache는 memory-only이며 volume·RDB·AOF·host port가 없다. PostgreSQL만 원본 데이터이고 cache loss는 허용한다.

[현재 처리]

- 외부 데이터 신뢰 경계 High: **해소안 반영.** 공개 사용자 cache value를 제3자 Upstash disk·backup에 보내지 않는다. Docker host memory 접근은 단일 host 잔여 위험으로 명시했다.
- 장애·복구 Medium: **반영.** backend recreate 방식과 자동 재연결 과장 표현을 삭제하고 같은 backend container ID에서 Redis stop/start를 검증한다.
- command budget Medium: **반영.** 공개 배포가 Free Tier command quota를 사용하지 않으며 active read/write health probe를 readiness·scrape에서 제거하고 수동 검증으로 제한한다.
- topology 최소화: Redis는 backend만 연결되는 `cache` internal network, 128MB `allkeys-lru`이고 별도 password·volume을 만들지 않는다. Grafana도 파일 provisioning으로 persistent volume을 만들지 않아 영속 volume은 PostgreSQL·Prometheus 두 개다.

[검증 예정]

- 변경된 인프라·운영 owner는 Compose isolation, persistence off, memory ceiling, cold start와 same-process reconnect 검증을 재검토한다.
- 변경된 개인정보·데이터 owner는 cache value가 Docker host 밖으로 나가지 않는지, DEMO cache 0건과 삭제·TTL 경계를 재검토한다.
- 두 reviewer의 미해결 Blocker/High와 새 사용자 결정이 없을 때만 Gate 2 변경분을 통과시킨다.

##### Docker Redis 변경분 선택 재리뷰 결과

- 인프라·운영 1차: anonymous volume, 개발 Compose 범위, exact 내부 Redis URL, cold-start 순서 Medium 4건. **전부 반영.**
- 개인정보·데이터 1차: DEMO cache 0건과 Google cache 삭제·TTL 검증 Medium 2건. **전부 반영.**
- 인프라·운영 최종: **통과 — 미해결 finding 없음.** Redis/Grafana `tmpfs`, `Type=volume` assertion, 개발 Compose 보존, exact `redis://redis:6379`, service-selective cold start와 backend ID 불변 복구를 확인했다.
- 개인정보·데이터 최종: **통과 — 미해결 finding 없음.** DEMO cache service·Redis command 0건, prod TTL 900초/90초, 변경·삭제·fallback·project 삭제 검증을 확인했다.
- 증명 경계: 구현 전 설계 변경분 통과일 뿐 Docker image·Compose·test·실행 성공을 증명하지 않는다.
- Gate 2 변경분 판정: **통과.**

## Gate 3 — 사용자 승인

- 상태: **승인 대기**
- 현재 사실·목표: query/localStorage bearer와 공유 없는 현재 app을 browser-bound cookie/CSRF, 방문자별 24시간 DEMO, scheduler-only worker, 분리된 Docker·관측 stack으로 바꾼다.
- 권장·제외 대안: 단일 구현자와 단계별 reviewer, HttpOnly JWT cookie, 방문자별 DB User를 선택한다. 구현자 2명, 매 단계 reviewer 2명, bearer 유지, 공유/Google demo 계정은 제외한다.
- Gate 2 finding: 최초 1차 Blocker 1·High 8·Medium 6·Low 1과 Docker Redis 변경분의 High 1·Medium 8을 모두 반영했고 선택 재리뷰의 미해결 finding은 없다.
- 구현 범위: 위 3단계와 각 단계 완료 조건. 1단계부터 순서대로 구현하며 단계 종료 review·검증을 통과해야 다음 단계로 간다.
- 구현 비범위: Deep scan, Google Production 심사와 계정 전체 삭제 정책, 외부 uptime monitor, Kubernetes·다중 replica·CI/CD 자동 배포, registry push, 현재 local DB·volume·stash 이전·삭제.
- 완료 조건·검증·rollback: §13·§14를 따른다. local, CI, deployed 증거를 분리하며 public route와 외부 서비스 변경은 해당 시점 승인을 받는다.
- 남은 위험: 단일 Mac mini와 내부 Prometheus/Grafana가 함께 중단되면 자체 Discord alert가 발송되지 않는다. `.env.production` secret은 Git에는 없지만 local Docker 관리자에게 보일 수 있다. Docker host memory/swap에는 cache value가 일시적으로 존재하고 fallback-eligible 실패에서는 삭제된 Task의 stale 요약이 같은 owner에게 최대 15분 표시될 수 있다. Google 실제 login은 Testing 등록 사용자로 제한된다.
- 사용자 결정 항목: 지금은 이 설계의 구현 착수 여부. hostname·외부 변경·후속 개인정보/uptime/cleanup은 §15 시점에 별도 결정한다.
- 재승인 조건: reviewer finding으로 인증 방식, 보존기간, 배포 topology, DB 삭제 범위, 외부 서비스 범위가 달라지는 경우.
- 다음 동작: Gate 2 리뷰 결과와 반영된 최종 설계를 사용자에게 제시하고 별도 구현 승인을 기다린다.
