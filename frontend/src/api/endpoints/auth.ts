import apiClient from '../client';
import type { ApiResponse } from '../types';
import type { Session } from '@/store/authStore';

export const authApi = {
  session: async () => {
    const response = await apiClient.get<ApiResponse<Session>>('/auth/session');
    return response.data.data;
  },
  demo: async () => {
    const response = await apiClient.post<ApiResponse<Session>>('/auth/demo');
    return response.data.data;
  },
  logout: async () => {
    await apiClient.post('/auth/logout');
  },
};
