import apiClient from '../client';
import type { ApiResponse } from '../types';
import type { OutboxEntry } from '@/types/outbox';

// ===== 사용자별 Outbox 관측 =====

export const outboxApi = {
  // GET /api/calendar-outbox?status=&taskId=
  getOutboxList: async (params?: { status?: string; taskId?: number }) => {
    const response = await apiClient.get<ApiResponse<OutboxEntry[]>>(
      '/calendar-outbox',
      { params }
    );
    return response.data.data;
  },

  // GET /api/calendar-outbox/:outboxId
  getOutbox: async (outboxId: number) => {
    const response = await apiClient.get<ApiResponse<OutboxEntry>>(
      `/calendar-outbox/${outboxId}`
    );
    return response.data.data;
  },
};
