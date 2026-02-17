import { create } from 'zustand';

interface AuthState {
  token: string | null;
  userId: number | null;
  isAuthenticated: boolean;
  login: (token: string, userId: number) => void;
  logout: () => void;
}

/**
 * JWT exp 클레임 기준으로 만료 여부 확인
 * - exp는 Unix timestamp (초 단위)
 * - 파싱 실패 시 만료된 것으로 취급
 */
function isTokenValid(token: string): boolean {
  try {
    const base64Url = token.split('.')[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const payload = JSON.parse(atob(base64));
    const expMs = payload.exp * 1000; // 밀리초로 변환
    return Date.now() < expMs;
  } catch {
    return false;
  }
}

function getInitialToken(): string | null {
  const token = localStorage.getItem('jwt_token');
  if (!token || !isTokenValid(token)) {
    // 만료됐으면 localStorage도 같이 정리
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('user_id');
    return null;
  }
  return token;
}

const initialToken = getInitialToken();

export const useAuthStore = create<AuthState>((set) => ({
  token: initialToken,
  userId: initialToken ? Number(localStorage.getItem('user_id')) || null : null,
  isAuthenticated: initialToken !== null,

  login: (token: string, userId: number) => {
    localStorage.setItem('jwt_token', token);
    localStorage.setItem('user_id', String(userId));
    set({ token, userId, isAuthenticated: true });
  },

  logout: () => {
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('user_id');
    set({ token: null, userId: null, isAuthenticated: false });
  },
}));
