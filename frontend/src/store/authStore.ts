import { create } from 'zustand';

export interface Session {
  authenticated: boolean;
  userType: 'GOOGLE' | 'DEMO' | null;
  expiresAt: string | null;
}

interface AuthState extends Session {
  initialized: boolean;
  setSession: (session: Session) => void;
  clearSession: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  authenticated: false,
  userType: null,
  expiresAt: null,
  initialized: false,
  setSession: (session) => set({ ...session, initialized: true }),
  clearSession: () => set({
    authenticated: false,
    userType: null,
    expiresAt: null,
    initialized: true,
  }),
}));
