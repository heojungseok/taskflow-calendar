import { create } from 'zustand';

export interface Session {
  authenticated: boolean;
  userType: 'GOOGLE' | 'DEMO' | null;
  expiresAt: string | null;
}

interface AuthState extends Session {
  initialized: boolean;
  generation: number;
  setSession: (session: Session) => void;
  clearSession: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  authenticated: false,
  userType: null,
  expiresAt: null,
  initialized: false,
  generation: 0,
  setSession: (session) => set((state) => ({
    ...session,
    initialized: true,
    generation: state.generation + 1,
  })),
  clearSession: () => set((state) => ({
    authenticated: false,
    userType: null,
    expiresAt: null,
    initialized: true,
    generation: state.generation + 1,
  })),
}));
