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
      // JWT payload에서 userId 추출
      const userId = parseUserIdFromJwt(token);
      login(token, userId);
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

/**
 * JWT payload에서 userId(sub) 추출
 * JWT 구조: header.payload.signature (Base64URL 인코딩)
 */
function parseUserIdFromJwt(token: string): number {
  try {
    const base64Url = token.split('.')[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const payload = JSON.parse(atob(base64));
    return Number(payload.sub) || 0;
  } catch {
    console.error('Failed to parse JWT payload');
    return 0;
  }
}
