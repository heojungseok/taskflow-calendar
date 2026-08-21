import { BrowserRouter, Routes, Route, Navigate, Outlet } from 'react-router';
import Login from './pages/Login';
import OAuthCallback from './pages/OAuthCallback';
import ProjectsPage from './pages/ProjectsPage';
import TaskListPage from './pages/TaskListPage';
import TaskDetailPage from './pages/TaskDetailPage';
import OutboxPage from './pages/OutboxPage';
import Header from './pages/Header';
import PrivacyPage from './pages/PrivacyPage';
import { useAuthStore } from './store/authStore';
import { authApi } from './api/endpoints/auth';
import { useEffect } from 'react';

function AuthLayout() {
  const authenticated = useAuthStore((state) => state.authenticated);

  if (!authenticated) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="min-h-screen bg-[var(--paper)]">
      <Header />
      <main className="max-w-5xl mx-auto px-6 py-10">
        <Outlet />
      </main>
    </div>
  );
}

function App() {
  const initialized = useAuthStore((state) => state.initialized);
  const setSession = useAuthStore((state) => state.setSession);
  const clearSession = useAuthStore((state) => state.clearSession);

  useEffect(() => {
    authApi.session().then(setSession).catch(clearSession);
  }, [setSession, clearSession]);

  if (!initialized) {
    return <div className="min-h-screen grid place-items-center">불러오는 중</div>;
  }

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/oauth/callback" element={<OAuthCallback />} />
        <Route path="/privacy" element={<PrivacyPage />} />

        <Route element={<AuthLayout />}>
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/:projectId/tasks" element={<TaskListPage />} />
          <Route path="/tasks/:taskId" element={<TaskDetailPage />} />
          <Route path="/admin/outbox" element={<OutboxPage />} />
        </Route>

        <Route path="/" element={<Navigate to="/projects" replace />} />
        <Route path="/tasks" element={<Navigate to="/projects" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
