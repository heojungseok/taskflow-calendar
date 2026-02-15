import { useState } from 'react';
import apiClient from '@/api/client';

interface ApiResponse<T> {
  success: boolean;
  data: T;
}

interface AuthorizeUrlResponse {
  authorizeUrl: string;
}

/**
 * 로그인 페이지 (MVP: Google OAuth만)
 */
export default function Login() {
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState('');

  const handleGoogleLogin = async () => {
    setError('');
    setIsLoading(true);

    try {
      // 1. /authorize 호출하여 Google 인증 URL 획득
      const response = await apiClient.get<ApiResponse<AuthorizeUrlResponse>>(
        '/oauth/google/authorize'
      );

      // 2. Google 로그인 페이지로 리다이렉트
      window.location.href = response.data.data.authorizeUrl;
    } catch (err) {
      console.error('Failed to get Google OAuth URL:', err);
      setError('Google 로그인을 시작할 수 없습니다. 다시 시도해주세요.');
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-100">
      <div className="bg-white p-8 rounded-lg shadow-md w-96">
        <h1 className="text-2xl font-bold mb-6 text-center">TaskFlow Calendar</h1>

        {error && (
          <div className="mb-4 p-3 bg-red-100 border border-red-400 text-red-700 rounded">
            {error}
          </div>
        )}

        {/* Google 공식 Sign-In 버튼 스타일 */}
        <button
          onClick={handleGoogleLogin}
          disabled={isLoading}
          className="w-full bg-white text-gray-700 border border-gray-300 py-3 px-4 rounded hover:bg-gray-50 disabled:bg-gray-100 disabled:cursor-not-allowed flex items-center justify-center gap-3 font-medium shadow-sm transition-colors"
        >
          {isLoading ? (
            <>
              <span className="animate-spin">⏳</span>
              <span>처리 중...</span>
            </>
          ) : (
            <>
              {/* Google "G" 로고 SVG */}
              <svg width="18" height="18" viewBox="0 0 18 18" xmlns="http://www.w3.org/2000/svg">
                <g fill="none" fillRule="evenodd">
                  <path
                    d="M17.64 9.205c0-.639-.057-1.252-.164-1.841H9v3.481h4.844a4.14 4.14 0 0 1-1.796 2.716v2.259h2.908c1.702-1.567 2.684-3.875 2.684-6.615Z"
                    fill="#4285F4"
                  />
                  <path
                    d="M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332A8.997 8.997 0 0 0 9 18Z"
                    fill="#34A853"
                  />
                  <path
                    d="M3.964 10.71A5.41 5.41 0 0 1 3.682 9c0-.593.102-1.17.282-1.71V4.958H.957A8.996 8.996 0 0 0 0 9c0 1.452.348 2.827.957 4.042l3.007-2.332Z"
                    fill="#FBBC05"
                  />
                  <path
                    d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0A8.997 8.997 0 0 0 .957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58Z"
                    fill="#EA4335"
                  />
                </g>
              </svg>
              <span>Google로 로그인</span>
            </>
          )}
        </button>

        <p className="text-sm text-gray-600 text-center mt-4">
          계정이 없으면 자동으로 생성됩니다
        </p>
      </div>
    </div>
  );
}
