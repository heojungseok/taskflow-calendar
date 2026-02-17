import { useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { cx, clsx } from '@/styles/cx';

export default function Header() {
  const navigate = useNavigate();
  const location = useLocation();
  const logout = useAuthStore((state) => state.logout);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const navItem = (path: string, label: string) => {
    const active = location.pathname.startsWith(path);
    return (
      <button
        onClick={() => navigate(path)}
        className={clsx(
          'text-xs font-medium tracking-wide transition-colors duration-150',
          active ? 'text-[#c8c8d4]' : 'text-[#4a4a5a] hover:text-[#7a7a90]',
        )}
      >
        {label}
      </button>
    );
  };

  return (
    <header className={`${cx.header} flex items-center justify-between`}>
      <div className="flex items-center gap-5">
        {/* 로고 */}
        <button
          onClick={() => navigate('/projects')}
          className="text-xs font-semibold tracking-[0.12em] text-[#3b5bff] uppercase"
        >
          TaskFlow
        </button>

        {/* 구분선 */}
        <span className="w-px h-3.5 bg-[#1e1e2a]" />

        {/* 네비게이션 */}
        <nav className="flex items-center gap-4">
          {navItem('/projects', '프로젝트')}
          {navItem('/admin/outbox', 'Outbox')}
        </nav>
      </div>

      <button onClick={handleLogout} className={cx.btn.ghost}>
        로그아웃
      </button>
    </header>
  );
}
