import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';

/**
 * Google OAuth 콜백 처리 페이지
 * URL에서 token 추출 → localStorage 저장 → 대시보드 이동
 */
export default function OAuthCallback() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const login = useAuthStore((state) => state.login);

  useEffect(() => {
    const token = searchParams.get('token');
    const error = searchParams.get('error');

    if (token) {
      // JWT 저장 (userId는 임시로 0 설정, 실제로는 JWT 파싱 필요)
      login(token, 0);
      console.log('OAuth login successful, redirecting to /tasks');
      navigate('/tasks');
    } else if (error) {
      console.error('OAuth login failed:', error);
      alert(`로그인 실패: ${error}`);
      navigate('/login');
    } else {
      // token도 error도 없으면 로그인 페이지로
      navigate('/login');
    }
  }, [searchParams, navigate, login]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-100">
      <div className="text-center">
        <h2 className="text-xl font-semibold mb-2">로그인 처리 중...</h2>
        <p className="text-gray-600">잠시만 기다려주세요</p>
      </div>
    </div>
  );
}
