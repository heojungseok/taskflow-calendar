import { useState } from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import apiClient from '@/api/client';
import { cx, clsx, PIPELINE } from '@/styles/cx';
import { authApi } from '@/api/endpoints/auth';
import { useAuthStore } from '@/store/authStore';
import { useNavigate } from 'react-router-dom';

interface ApiResponse<T> {
  success: boolean;
  data: T;
}

interface AuthorizeUrlResponse {
  authorizeUrl: string;
}

export default function Login() {
  const [isLoading, setIsLoading] = useState(false);
  const [isDemoLoading, setIsDemoLoading] = useState(false);
  const [error, setError] = useState('');
  const reduceMotion = useReducedMotion();
  const navigate = useNavigate();
  const setSession = useAuthStore((state) => state.setSession);

  const handleDemoLogin = async () => {
    setError('');
    setIsDemoLoading(true);
    try {
      setSession(await authApi.demo());
      navigate('/projects');
    } catch {
      setError('데모를 시작할 수 없습니다. 잠시 후 다시 시도하세요.');
      setIsDemoLoading(false);
    }
  };

  const handleGoogleLogin = async () => {
    setError('');
    setIsLoading(true);
    try {
      const response = await apiClient.get<ApiResponse<AuthorizeUrlResponse>>(
        '/oauth/google/authorize'
      );
      window.location.href = response.data.data.authorizeUrl;
    } catch (err) {
      console.error('Failed to get Google OAuth URL:', err);
      setError('Google 로그인을 시작할 수 없습니다. 잠시 후 다시 시도하세요.');
      setIsLoading(false);
    }
  };

  const step = (i: number) =>
    reduceMotion
      ? {}
      : {
          initial: { opacity: 0, y: 6 },
          animate: { opacity: 1, y: 0 },
          transition: {
            duration: 0.4,
            delay: i * 0.08,
            ease: [0.2, 0, 0, 1] as [number, number, number, number],
          },
        };

  return (
    <div className={clsx(cx.page, 'min-h-screen grid place-items-center px-6')}>
      <main className="w-full max-w-[400px] text-center py-16">
        <motion.h1
          {...step(0)}
          className="font-[family-name:var(--font-display)] text-[clamp(38px,10vw,52px)] leading-[0.9] font-extrabold tracking-[-0.04em] text-[var(--ink)]"
        >
          TASKFLOW
        </motion.h1>

        <motion.div {...step(1)} className="mt-5">
          <p className="text-[16px] leading-[1.6] text-[var(--ink)]">
            할 일을 저장하면 구글 캘린더에 반영됩니다.
          </p>
          <p className="mt-1 text-[14px] leading-[1.6] text-[var(--ink-2)]">
            연동이 실패해도 기록은 남고 다시 시도합니다.
          </p>
        </motion.div>

        {/* 시그니처 — 색 언어를 여기서 한 번 가르친다 */}
        <motion.div
          {...step(2)}
          aria-label="동기화 단계"
          className="relative mt-9"
        >
          <span
            aria-hidden
            className="absolute top-[4px] left-[12.5%] right-[12.5%] h-px bg-[var(--rule-strong)]"
          />
          <ol className="relative grid grid-cols-4">
            {PIPELINE.map((stage) => (
              <li key={stage.en} className="flex flex-col items-center gap-2.5">
                <span
                  aria-hidden
                  className={clsx(
                    'w-2 h-2 rounded-[1px]',
                    stage.en === 'PROCESSING' && 'running-dot'
                  )}
                  style={{ backgroundColor: stage.mark }}
                />
                <span className="text-[13px] text-[var(--ink-2)]">
                  {stage.ko}
                </span>
              </li>
            ))}
          </ol>
        </motion.div>

        <motion.div {...step(3)} className="mt-10">
          {error && (
            <div role="alert" className={clsx(cx.errorBox, 'mb-3 text-left')}>
              {error}
            </div>
          )}

          <button
            onClick={handleDemoLogin}
            disabled={isDemoLoading || isLoading}
            className={clsx(cx.btn.primary, 'w-full py-3 text-[14px]')}
          >
            {isDemoLoading ? '데모를 준비하는 중' : '데모로 둘러보기'}
          </button>

          <button
            onClick={handleGoogleLogin}
            disabled={isLoading || isDemoLoading}
            className={clsx(
              cx.btn.secondary,
              'mt-3 w-full inline-flex items-center justify-center gap-2.5 py-3 text-[14px]'
            )}
          >
            {isLoading ? (
              <>
                <span
                  aria-hidden
                  className="w-3.5 h-3.5 border-2 border-current border-t-transparent rounded-full animate-spin"
                />
                연결하는 중
              </>
            ) : (
              <>
                <svg width="17" height="17" viewBox="0 0 18 18" aria-hidden>
                  <path d="M17.64 9.205c0-.639-.057-1.252-.164-1.841H9v3.481h4.844a4.14 4.14 0 0 1-1.796 2.716v2.259h2.908c1.702-1.567 2.684-3.875 2.684-6.615Z" fill="#4285F4" />
                  <path d="M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332A8.997 8.997 0 0 0 9 18Z" fill="#34A853" />
                  <path d="M3.964 10.71A5.41 5.41 0 0 1 3.682 9c0-.593.102-1.17.282-1.71V4.958H.957A8.996 8.996 0 0 0 0 9c0 1.452.348 2.827.957 4.042l3.007-2.332Z" fill="#FBBC05" />
                  <path d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0A8.997 8.997 0 0 0 .957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58Z" fill="#EA4335" />
                </svg>
                Google로 로그인
              </>
            )}
          </button>

          <p className="mt-3 text-[13px] text-[var(--ink-3)]">
            데모 데이터는 방문자별로 분리되며 24시간 뒤 삭제됩니다.
          </p>
          <a
            href="/privacy"
            className="mt-4 inline-block text-[13px] text-[var(--ink-3)] underline underline-offset-4"
          >
            Privacy Policy
          </a>
        </motion.div>
      </main>
    </div>
  );
}
