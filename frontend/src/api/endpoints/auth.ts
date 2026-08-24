import apiClient from '../client';
import type { ApiResponse } from '../types';
import type { Session } from '@/store/authStore';

export const authApi = {
  googleAuthorizeUrl: async (signal?: AbortSignal) => {
    const response = await apiClient.get<ApiResponse<{ authorizeUrl: string }>>(
      '/oauth/google/authorize',
      {
        signal,
        validateStatus: status => (status >= 200 && status < 300) || status === 401,
      }
    );
    if (response.status === 401) throw new Error('Google authorization requires authentication');
    return response.data.data.authorizeUrl;
  },
  session: async () => {
    const response = await apiClient.get<ApiResponse<Session>>('/auth/session');
    return response.data.data;
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
    await apiClient.post('/oauth/google/disconnect');
  },
};
