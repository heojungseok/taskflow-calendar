import { useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';

export default function Header() {
  const navigate = useNavigate();
  const location = useLocation();
  const logout = useAuthStore((state) => state.logout);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const isActive = (path: string) =>
    location.pathname.startsWith(path)
      ? 'text-blue-600 font-medium'
      : 'text-gray-600 hover:text-gray-900';

  return (
    <header className="bg-white border-b border-gray-200 px-6 py-3 flex items-center justify-between">
      <div className="flex items-center gap-6">
        <span
          className="font-bold text-blue-600 text-lg cursor-pointer"
          onClick={() => navigate('/projects')}
        >
          TaskFlow
        </span>

        <nav className="flex items-center gap-4 text-sm">
          <button
            onClick={() => navigate('/projects')}
            className={isActive('/projects')}
          >
            프로젝트
          </button>
          <button
            onClick={() => navigate('/admin/outbox')}
            className={isActive('/admin')}
          >
            Outbox 모니터
          </button>
        </nav>
      </div>

      <button
        onClick={handleLogout}
        className="text-sm text-gray-500 hover:text-gray-700"
      >
        로그아웃
      </button>
    </header>
  );
}
