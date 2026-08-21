import { useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { cx, clsx } from '@/styles/cx';
import { authApi } from '@/api/endpoints/auth';
import { useState } from 'react';

export default function Header() {
  const navigate = useNavigate();
  const location = useLocation();
  const clearSession = useAuthStore((state) => state.clearSession);
  const userType = useAuthStore((state) => state.userType);
  const [disconnecting, setDisconnecting] = useState(false);

  const handleLogout = async () => {
    try {
      await authApi.logout();
    } finally {
      clearSession();
      navigate('/login');
    }
  };

  const handleDisconnect = async () => {
    if (!window.confirm('Google Calendar 연결을 해제할까요? 캘린더 동기화가 중단되며 기존 일정은 삭제되지 않습니다.')) {
      return;
    }

    setDisconnecting(true);
    try {
      await authApi.disconnectGoogle();
    } catch {
      window.alert('연결 해제 결과를 확인하지 못했습니다. 다시 로그인한 뒤 상태를 확인해주세요.');
    } finally {
      clearSession();
      navigate('/login');
    }
  };

  // 현재 위치는 색이 아니라 밑줄로 알린다 — 색은 상태 전용이므로
  const navItem = (path: string, label: string) => {
    const active = location.pathname.startsWith(path);
    return (
      <button
        key={path}
        onClick={() => navigate(path)}
        aria-current={active ? 'page' : undefined}
        className={clsx(
          'relative py-1 text-[14px] transition-colors duration-150',
          active
            ? 'text-[var(--ink)] font-medium'
            : 'text-[var(--ink-3)] hover:text-[var(--ink)]'
        )}
      >
        {label}
        {active && (
          <span
            aria-hidden
            className="absolute bottom-0 left-0 right-0 h-[2px] bg-[var(--ink)]"
          />
        )}
      </button>
    );
  };

  return (
    <header className={cx.header}>
      <div className="max-w-5xl mx-auto flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-4 sm:gap-6">
          <button
            onClick={() => navigate('/projects')}
            className="font-[family-name:var(--font-display)] text-[15px] font-extrabold tracking-[-0.02em] text-[var(--ink)]"
          >
            TasKFlow
          </button>

          <span aria-hidden className="w-px h-4 bg-[var(--rule)]" />

          <nav className="flex items-center gap-5">
            {navItem('/projects', '프로젝트')}
            {navItem('/admin/outbox', '동기화 현황')}
          </nav>
        </div>

        <div className="flex flex-wrap items-center gap-3 sm:justify-end sm:gap-4">
          <a href="/privacy" className={cx.btn.ghost}>Privacy</a>
          <a href="/terms" className={cx.btn.ghost}>Terms</a>
          {userType === 'GOOGLE' && (
            <button onClick={handleDisconnect} disabled={disconnecting} className={cx.btn.danger}>
              {disconnecting ? '연결 해제 중...' : 'Google 연결 해제'}
            </button>
          )}
          <button onClick={handleLogout} className={cx.btn.ghost}>
            로그아웃
          </button>
        </div>
      </div>
    </header>
  );
}
