import { BrowserRouter, Routes, Route, Navigate, Outlet } from 'react-router';
import Login from './pages/Login';
import OAuthCallback from './pages/OAuthCallback';
import ProjectsPage from './pages/ProjectsPage';
import TaskListPage from './pages/TaskListPage';
import Header from './pages/Header';
import { useAuthStore } from './store/authStore';

// 인증된 사용자만 접근 가능한 레이아웃
function AuthLayout() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <Header />
      <main className="max-w-5xl mx-auto px-6 py-6">
        <Outlet />
      </main>
    </div>
  );
}

function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* 공개 라우트 */}
        <Route path="/login" element={<Login />} />
        <Route path="/oauth/callback" element={<OAuthCallback />} />

        {/* 인증 필요 라우트 */}
        <Route element={<AuthLayout />}>
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/:projectId/tasks" element={<TaskListPage />} />
          {/* 다음 단계에서 추가 예정 */}
          {/* <Route path="/tasks/:taskId" element={<TaskDetailPage />} /> */}
          {/* <Route path="/admin/outbox" element={<OutboxPage />} /> */}
        </Route>

        {/* 루트 리다이렉트 */}
        <Route path="/" element={<Navigate to="/projects" replace />} />
        {/* 기존 /tasks 라우트 호환성 유지 */}
        <Route path="/tasks" element={<Navigate to="/projects" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
