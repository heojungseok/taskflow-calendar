import apiClient from '../client';

export interface CalendarSyncStatus {
  taskId: number;
  lastOutboxStatus: string | null;
  lastSyncedAt: string | null;
  lastOutboxError: string | null;
  calendarEventId: string | null;
}

export const calendarApi = {
  getSyncStatus: async (taskId: number) => {
    const response = await apiClient.get<CalendarSyncStatus>(`/tasks/${taskId}/calendar-sync`);
    return response.data;
  },

  getAuthorizeUrl: async () => {
    const response = await apiClient.get<{ authorizeUrl: string }>('/oauth/google/authorize');
    return response.data.authorizeUrl;
  },
};
