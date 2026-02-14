import { create } from 'zustand';

interface AuthState {
  token: string | null;
  userId: number | null;
  isAuthenticated: boolean;
  login: (token: string, userId: number) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  token: localStorage.getItem('jwt_token'),
  userId: Number(localStorage.getItem('user_id')) || null,
  isAuthenticated: !!localStorage.getItem('jwt_token'),

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
