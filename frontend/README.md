# TaskFlow Calendar - Frontend

## 기술 스택
- **React 19.2** + TypeScript
- **Vite 7.3** (빌드 도구)
- **TanStack Query 5.90** (서버 상태 관리)
- **Axios 1.13** (HTTP 클라이언트)
- **Zustand 5.0** (클라이언트 상태 관리)
- **React Router 7.13** (라우팅)
- **Tailwind CSS 4.1** (스타일링)

## 프로젝트 구조
```
frontend/src/
├── api/              # API 클라이언트
│   ├── client.ts         # Axios 인스턴스 (JWT 인터셉터)
│   └── endpoints/        # API 엔드포인트
│       ├── auth.ts       # 인증 API
│       ├── tasks.ts      # Task CRUD API
│       └── calendar.ts   # Calendar 동기화 API
├── components/       # 재사용 컴포넌트
│   ├── common/           # 공통 UI 컴포넌트
│   ├── layout/           # 레이아웃 컴포넌트
│   └── features/         # 기능별 컴포넌트
├── pages/            # 페이지 컴포넌트
│   ├── Login.tsx         # 로그인 페이지
│   ├── ProjectListPage.tsx
│   ├── TaskListPage.tsx  # Task 목록 + 상세 모달 + 주간 요약
│   └── OAuthCallback.tsx
├── hooks/            # 커스텀 훅
├── store/            # Zustand 스토어
│   └── authStore.ts      # 인증 상태 관리
├── types/            # TypeScript 타입
│   └── task.ts           # Task 도메인 타입
├── utils/            # 유틸리티 함수
├── App.tsx           # 앱 라우팅
└── main.tsx          # 앱 진입점
```

## 개발 서버 실행

### 1. 의존성 설치
```bash
npm install
```

### 2. 개발 서버 시작
```bash
npm run dev
```

프론트엔드 서버: http://localhost:3000

### 3. 빌드
```bash
npm run build
```

### 4. 프리뷰 (빌드 결과 확인)
```bash
npm run preview
```

## 백엔드 연동

**Vite Proxy 설정 완료:**
- 프론트엔드: `http://localhost:3000`
- 백엔드: `http://localhost:8080`
- API 호출 시: `/api/*` → 자동으로 백엔드로 프록시

**예시:**
```typescript
// 프론트엔드에서 axios 호출
axios.get('/api/tasks/1')
// → 실제 요청: http://localhost:8080/api/tasks/1
```

## 인증 플로우

1. **Google OAuth 진입**: 백엔드에서 OAuth URL을 받아 Google 로그인 페이지로 이동
2. **콜백 처리**: `/oauth/callback`에서 JWT를 전달받아 저장
3. **JWT 저장**: `localStorage`에 토큰 저장
4. **자동 인증**: Axios 인터셉터가 모든 요청에 JWT 헤더 추가
5. **401 처리**: 토큰 만료 시 자동 로그아웃 및 `/login`으로 리다이렉트

## 상태 관리

### 1. 서버 상태 (TanStack Query)
- API 데이터 캐싱 및 동기화
- 자동 재시도 (1회)
- Window focus 시 자동 refetch 비활성화

### 2. 클라이언트 상태 (Zustand)
- JWT 토큰 및 사용자 ID 관리
- 인증 상태 (`isAuthenticated`) 전역 관리

## 구현 현황

### 완료
- [x] 프로젝트 초기화
- [x] 인증 기반 구축
- [x] 프로젝트 목록/선택
- [x] Task CRUD (목록/생성/수정/삭제)
- [x] 상태 전이 UI
- [x] Task 동기화 상태 조회
- [x] Google OAuth 연동 UI
- [x] Task 상세 모달
- [x] 프로젝트 주간 요약 UI

### 이후 후보
- [ ] Outbox 관측 화면
- [ ] 보드(칸반) 뷰
- [ ] 캘린더 뷰
- [ ] 요약 실패 원인 세분화 UI
- [ ] Gemini quota 초과 시 fallback 요약

## API 엔드포인트 예시

### 인증
```typescript
import { authApi } from '@/api/endpoints/auth';

// 로그인
const response = await authApi.login({ email, password });
// { token: 'xxx', userId: 1 }
```

### Task 관리
```typescript
import { tasksApi } from '@/api/endpoints/tasks';

// Task 목록 조회
const tasks = await tasksApi.getTasks(projectId);

// Task 생성
const newTask = await tasksApi.createTask(projectId, {
  title: 'New Task',
  dueAt: '2026-02-20T10:00:00Z',
  calendarSyncEnabled: true,
});

// Task 수정
const updatedTask = await tasksApi.updateTask(taskId, {
  title: 'Updated Title',
});

// Task 삭제
await tasksApi.deleteTask(taskId);

// 상태 변경
const task = await tasksApi.changeStatus(taskId, 'IN_PROGRESS');
```

### 프로젝트 주간 요약
```typescript
import { projectsApi } from '@/api/endpoints/projects';

const summary = await projectsApi.generateWeeklySummary(projectId);
// {
//   synced: { summary, highlights, risks, nextActions, ... },
//   unsynced: { summary, highlights, risks, nextActions, ... },
// }
```

### Calendar 동기화
```typescript
import { calendarApi } from '@/api/endpoints/calendar';

// 동기화 상태 조회
const syncStatus = await calendarApi.getSyncStatus(taskId);
// {
//   taskId: 1,
//   lastOutboxStatus: 'SUCCESS',
//   lastSyncedAt: '2026-02-14T12:00:00Z',
//   lastOutboxError: null,
//   calendarEventId: 'abc123',
// }

// Google OAuth 인증 URL 조회
const authorizeUrl = await calendarApi.getAuthorizeUrl();
window.location.href = authorizeUrl;
```

## 문제 해결

### Tailwind CSS가 적용되지 않을 때
```bash
npm install -D @tailwindcss/postcss autoprefixer
```

### TypeScript 컴파일 에러
```bash
# tsconfig 확인
cat tsconfig.app.json

# baseUrl과 paths 설정 확인
```

### API 호출 CORS 에러
- Vite Proxy 설정이 정상적으로 동작하는지 확인
- 백엔드 서버가 `http://localhost:8080`에서 실행 중인지 확인

## 다음 단계

1. Gemini quota 초과/키 미설정/기타 실패를 구분해서 노출
2. Outbox 모니터링 화면 구현 (`/admin/outbox`)
3. 보드(칸반) 뷰 / 캘린더 뷰 확장
4. 요약 fallback 또는 재시도 전략 연결

## 주간 요약 UX

- Task 목록 화면에서 프로젝트 단위 주간 요약을 생성할 수 있습니다.
- 요약은 `동기화된 일정` / `미동기화 일정` 2개 섹션으로 나뉘어 표시됩니다.
- Task 카드를 누르면 페이지 이동 대신 상세 모달이 열립니다.
- 상세 모달에서 제목, 설명, 시작일, 마감일, 상태를 수정할 수 있습니다.
- 상세 모달을 닫아도 목록 화면의 요약 결과는 유지됩니다.
