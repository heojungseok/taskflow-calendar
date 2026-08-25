import { BrowserRouter, Routes, Route, Navigate, Outlet } from 'react-router';
import Login from './pages/Login';
import OAuthCallback from './pages/OAuthCallback';
import ProjectsPage from './pages/ProjectsPage';
import TaskListPage from './pages/TaskListPage';
import TaskDetailPage from './pages/TaskDetailPage';
import OutboxPage from './pages/OutboxPage';
import Header from './pages/Header';
import HomePage from './pages/HomePage';
import PrivacyPage from './pages/PrivacyPage';
import TermsPage from './pages/TermsPage';
import { useAuthStore } from './store/authStore';
import { authApi } from './api/endpoints/auth';
import { useEffect, useState } from 'react';
import { MotionConfig } from 'framer-motion';
import SessionExpiryDialog from './components/SessionExpiryDialog';
import { listenForSessionEnded } from './lib/authBroadcast';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import NotFoundPage from './pages/NotFoundPage';
import { saveReturnPath } from './lib/authReturnPath';
import { isOAuthError } from './lib/oauthErrors';

function SessionLoading() {
  return (
    <main className="min-h-screen grid place-items-center" role="status" aria-live="polite">
      불러오는 중
    </main>
  );
}

function LoginRoute() {
  const authenticated = useAuthStore((state) => state.authenticated);
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const hasOAuthError = isOAuthError(searchParams.get('error'));
  const sessionChecked = (location.state as { sessionChecked?: boolean } | null)?.sessionChecked;

  if (hasOAuthError || (sessionChecked && !authenticated)) return <Login />;
  return <CheckedLoginRoute />;
}

function CheckedLoginRoute() {
  const setSession = useAuthStore((state) => state.setSession);
  const clearSession = useAuthStore((state) => state.clearSession);
  const authenticated = useAuthStore((state) => state.authenticated);
  const [checking, setChecking] = useState(true);

  useEffect(() => {
    let active = true;
    authApi.session()
      .then(session => {
        if (active) setSession(session);
      })
      .catch(() => {
        if (active) clearSession();
      })
      .finally(() => {
        if (active) setChecking(false);
      });
    return () => {
      active = false;
    };
  }, [setSession, clearSession]);

  if (checking) return <SessionLoading />;

  return authenticated ? <Navigate to="/projects" replace /> : <Login />;
}

function AuthLayout() {
  const setSession = useAuthStore((state) => state.setSession);
  const clearSession = useAuthStore((state) => state.clearSession);
  const initialized = useAuthStore((state) => state.initialized);
  const authenticated = useAuthStore((state) => state.authenticated);
  const navigate = useNavigate();

  useEffect(() => {
    let active = true;
    authApi.session()
      .then(session => {
        if (!active) return;
        if (!session.authenticated) saveReturnPath();
        setSession(session);
      })
      .catch(() => {
        if (active) clearSession();
      });
    return () => {
      active = false;
    };
  }, [setSession, clearSession]);

  useEffect(() => listenForSessionEnded(() => {
    clearSession();
    navigate('/login', { replace: true, state: { sessionChecked: true } });
  }), [clearSession, navigate]);

  if (!initialized) {
    return <SessionLoading />;
  }

  if (!authenticated) {
    return <Navigate to="/login" replace state={{ sessionChecked: true }} />;
  }

  return (
    <div className="min-h-screen bg-[var(--paper)]">
      <Header />
      <main className="max-w-5xl mx-auto px-6 py-10">
        <Outlet />
      </main>
      <SessionExpiryDialog />
    </div>
  );
}

function App() {
  return (
    <MotionConfig reducedMotion="user">
      <BrowserRouter>
        <Routes>
        <Route path="/login" element={<LoginRoute />} />
        <Route path="/oauth/callback" element={<OAuthCallback />} />
        <Route path="/privacy" element={<PrivacyPage />} />
        <Route path="/terms" element={<TermsPage />} />
        <Route path="/" element={<HomePage />} />

        <Route element={<AuthLayout />}>
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/:projectId/tasks" element={<TaskListPage />} />
          <Route path="/tasks/:taskId" element={<TaskDetailPage />} />
          <Route path="/admin/outbox" element={<OutboxPage />} />
        </Route>

        <Route path="/tasks" element={<Navigate to="/projects" replace />} />
        <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </BrowserRouter>
    </MotionConfig>
  );
}

export default App;
