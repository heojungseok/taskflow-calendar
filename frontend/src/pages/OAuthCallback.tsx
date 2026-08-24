import { useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { authApi } from '@/api/endpoints/auth';
import { cx, clsx } from '@/styles/cx';
import { clearReturnPath, consumeReturnPath } from '@/lib/authReturnPath';

/**
 * Google OAuth 콜백 처리 페이지
 * HttpOnly session cookie 확인 → 대시보드 이동
 */
export default function OAuthCallback() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const setSession = useAuthStore((state) => state.setSession);
  const handled = useRef(false);

  useEffect(() => {
    if (handled.current) return;
    handled.current = true;
    const error = searchParams.get('error');

    if (error) {
      clearReturnPath();
      alert(error === 'calendar_permission_required'
        ? 'Google Calendar 권한이 필요합니다. Google 계정에서 TaskFlow 연결을 해제한 뒤 다시 로그인하고 Calendar 권한을 허용해주세요.'
        : 'Google 로그인에 실패했습니다. 다시 시도해주세요.');
      navigate('/login');
    } else {
      authApi.session()
        .then((session) => {
          setSession(session);
          if (session.authenticated) {
            navigate(consumeReturnPath());
          } else {
            clearReturnPath();
            navigate('/login');
          }
        })
        .catch(() => {
          clearReturnPath();
          navigate('/login');
        });
    }
  }, [searchParams, navigate, setSession]);

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
