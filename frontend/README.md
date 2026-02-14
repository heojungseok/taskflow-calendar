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
│   └── Login.tsx         # 로그인 페이지
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

1. **로그인**: `/login` 페이지에서 이메일/비밀번호 입력
2. **JWT 저장**: `localStorage`에 토큰 저장
3. **자동 인증**: Axios 인터셉터가 모든 요청에 JWT 헤더 추가
4. **401 처리**: 토큰 만료 시 자동 로그아웃 및 `/login`으로 리다이렉트

## 상태 관리

### 1. 서버 상태 (TanStack Query)
- API 데이터 캐싱 및 동기화
- 자동 재시도 (1회)
- Window focus 시 자동 refetch 비활성화

### 2. 클라이언트 상태 (Zustand)
- JWT 토큰 및 사용자 ID 관리
- 인증 상태 (`isAuthenticated`) 전역 관리

## 구현 예정 기능

### Week 5 (우선순위)
- [x] 프로젝트 초기화
- [x] 인증 기반 구축
- [ ] Outbox 관측 화면
- [ ] Task 동기화 상태 조회

### Week 6
- [ ] Task CRUD (목록/생성/수정/삭제)
- [ ] 상태 전이 UI
- [ ] Task 이력 조회

### Week 7+
- [ ] 보드(칸반) 뷰
- [ ] 캘린더 뷰
- [ ] Google OAuth 연동 UI

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

1. Outbox 모니터링 화면 구현 (`/admin/outbox`)
2. Task 목록/생성/수정/삭제 UI 구현
3. Calendar 동기화 상태 뱃지 추가
4. Google OAuth 팝업 연동
