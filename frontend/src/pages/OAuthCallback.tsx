import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { cx, clsx } from '@/styles/cx';

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
    <div className={clsx(cx.page, 'min-h-screen grid place-items-center px-6')}>
      <main className="w-full max-w-[400px] text-center" role="status" aria-live="polite">
        {/* 로그인 화면과 같은 어휘 — 지금 파이프라인의 어디쯤인지 보여준다 */}
        <span
          aria-hidden
          className="running-dot inline-block w-2 h-2 rounded-[1px] mb-5"
          style={{ backgroundColor: 'var(--st-running-mark)' }}
        />
        <h2 className="text-[16px] text-[var(--ink)]">로그인하는 중</h2>
        <p className="mt-1 text-[14px] text-[var(--ink-2)]">
          구글 계정을 확인하고 있습니다.
        </p>
      </main>
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
