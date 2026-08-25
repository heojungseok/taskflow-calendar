import apiClient from '../client';
import type { ApiResponse } from '../types';
import { useAuthStore, type Session } from '@/store/authStore';

function ifCurrentGeneration<T>(generation: number, value: T) {
  return generation === useAuthStore.getState().generation ? value : undefined;
}

async function googleOAuthUrl(path: string, signal?: AbortSignal) {
  const response = await apiClient.get<ApiResponse<{ authorizeUrl: string }>>(path, {
    signal,
    validateStatus: status => (status >= 200 && status < 300) || status === 401,
  });
  if (response.status === 401) throw new Error('Google authorization requires authentication');
  return response.data.data.authorizeUrl;
}

export const authApi = {
  googleAuthorizeUrl: (signal?: AbortSignal) =>
    googleOAuthUrl('/oauth/google/authorize', signal),
  googleReconsentUrl: (signal?: AbortSignal) =>
    googleOAuthUrl('/oauth/google/reconsent', signal),
  session: async () => {
    const generation = useAuthStore.getState().generation;
    const response = await apiClient.get<ApiResponse<Session>>('/auth/session');
    return ifCurrentGeneration(generation, response.data.data);
  },
  sessionOrNull: async () => {
    const generation = useAuthStore.getState().generation;
    const response = await apiClient.get<ApiResponse<Session>>('/auth/session', {
      validateStatus: status => (status >= 200 && status < 300) || status === 401,
    });
    return ifCurrentGeneration(
      generation,
      response.status === 401 ? null : response.data.data
    );
  },
  demo: async () => {
    await apiClient.get('/auth/session');
    const response = await apiClient.post<ApiResponse<Session>>('/auth/demo');
    return response.data.data;
  },
  logout: async () => {
    await apiClient.post('/auth/logout');
  },
  disconnectGoogle: async () => {
    const response = await apiClient.post<ApiResponse<boolean>>('/oauth/google/disconnect');
    return response.data.data;
  },
};
