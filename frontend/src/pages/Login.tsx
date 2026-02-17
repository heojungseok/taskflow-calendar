import { useState } from 'react';
import { motion } from 'framer-motion';
import apiClient from '@/api/client';
import { useTheme } from '@/hooks/useTheme';
import { Sun, Moon } from 'lucide-react';
import { cx, clsx } from '@/styles/cx';

interface ApiResponse<T> {
  success: boolean;
  data: T;
}

interface AuthorizeUrlResponse {
  authorizeUrl: string;
}

export default function Login() {
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState('');
  const { isDark, toggle } = useTheme();

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
      setError('Google 로그인을 시작할 수 없습니다. 다시 시도해주세요.');
      setIsLoading(false);
    }
  };

  return (
    <div className={clsx(cx.page, 'flex items-center justify-center relative')}>
      {/* 테마 토글 */}
      <button
        onClick={toggle}
        className="absolute top-4 right-4 p-2 rounded-lg text-gray-400 dark:text-slate-400 hover:bg-gray-100 dark:hover:bg-surface-700 transition-colors"
        aria-label="테마 전환"
      >
        {isDark ? <Sun size={16} /> : <Moon size={16} />}
      </button>

      {/* 카드 */}
      <motion.div
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.25 }}
        className={clsx(cx.card, 'w-full max-w-sm px-8 py-10')}
      >
        {/* 로고 */}
        <div className="text-center mb-8">
          <h1 className="text-2xl font-bold text-accent tracking-tight mb-1">
            TaskFlow
          </h1>
          <p className={cx.text.meta}>Task와 캘린더를 하나로</p>
        </div>

        {/* 에러 */}
        {error && (
          <motion.div
            initial={{ opacity: 0, y: -4 }}
            animate={{ opacity: 1, y: 0 }}
            className={clsx(cx.errorBox, 'mb-4')}
          >
            {error}
          </motion.div>
        )}

        {/* Google 로그인 버튼 */}
        <button
          onClick={handleGoogleLogin}
          disabled={isLoading}
          className="w-full flex items-center justify-center gap-3 px-4 py-3 rounded-xl border border-gray-200 dark:border-surface-600 bg-white dark:bg-surface-700 text-gray-700 dark:text-slate-200 font-medium text-sm hover:bg-gray-50 dark:hover:bg-surface-600 hover:border-gray-300 dark:hover:border-surface-500 disabled:opacity-60 disabled:cursor-not-allowed transition-all shadow-sm"
        >
          {isLoading ? (
            <>
              <motion.div
                animate={{ rotate: 360 }}
                transition={{ repeat: Infinity, duration: 0.8, ease: 'linear' }}
                className="w-4 h-4 border-2 border-accent border-t-transparent rounded-full"
              />
              <span>처리 중...</span>
            </>
          ) : (
            <>
              {/* Google G 로고 */}
              <svg width="18" height="18" viewBox="0 0 18 18" xmlns="http://www.w3.org/2000/svg">
                <g fill="none" fillRule="evenodd">
                  <path d="M17.64 9.205c0-.639-.057-1.252-.164-1.841H9v3.481h4.844a4.14 4.14 0 0 1-1.796 2.716v2.259h2.908c1.702-1.567 2.684-3.875 2.684-6.615Z" fill="#4285F4" />
                  <path d="M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332A8.997 8.997 0 0 0 9 18Z" fill="#34A853" />
                  <path d="M3.964 10.71A5.41 5.41 0 0 1 3.682 9c0-.593.102-1.17.282-1.71V4.958H.957A8.996 8.996 0 0 0 0 9c0 1.452.348 2.827.957 4.042l3.007-2.332Z" fill="#FBBC05" />
                  <path d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0A8.997 8.997 0 0 0 .957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58Z" fill="#EA4335" />
                </g>
              </svg>
              <span>Google로 로그인</span>
            </>
          )}
        </button>

        <p className={clsx(cx.text.meta, 'text-center mt-4')}>
          계정이 없으면 자동으로 생성됩니다
        </p>
      </motion.div>
    </div>
  );
}
