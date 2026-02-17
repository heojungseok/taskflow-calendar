import apiClient from '../client';
import type { ApiResponse } from '../types';
import type { OutboxEntry } from '@/types/outbox';

// ===== Google OAuth =====

export const oauthApi = {
  // GET /api/oauth/google/authorize
  getAuthorizeUrl: async (): Promise<string> => {
    const response = await apiClient.get<ApiResponse<{ authorizeUrl: string }>>(
      '/oauth/google/authorize'
    );
    return response.data.data.authorizeUrl;
  },
};

// ===== Admin: Outbox 관측 =====

export const outboxApi = {
  // GET /api/admin/calendar-outbox?status=&taskId=
  getOutboxList: async (params?: { status?: string; taskId?: number }) => {
    const response = await apiClient.get<ApiResponse<OutboxEntry[]>>(
      '/admin/calendar-outbox',
      { params }
    );
    return response.data.data;
  },

  // GET /api/admin/calendar-outbox/:outboxId
  getOutbox: async (outboxId: number) => {
    const response = await apiClient.get<ApiResponse<OutboxEntry>>(
      `/admin/calendar-outbox/${outboxId}`
    );
    return response.data.data;
  },

  // GET /api/admin/calendar-outbox/trigger-worker
  triggerWorker: async () => {
    const response = await apiClient.get<ApiResponse<void>>(
      '/admin/calendar-outbox/trigger-worker'
    );
    return response.data;
  },
};
