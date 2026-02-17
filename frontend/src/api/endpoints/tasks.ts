import apiClient from '../client';
import type { ApiResponse } from '../types';
import type {
  Task,
  TaskCreateRequest,
  TaskUpdateRequest,
  TaskHistory,
  CalendarSyncStatus,
} from '@/types/task';

export const tasksApi = {
  // GET /api/projects/:projectId/tasks?status=&assigneeUserId=
  getTasks: async (projectId: number, params?: { status?: string; assigneeUserId?: number }) => {
    const response = await apiClient.get<ApiResponse<Task[]>>(
      `/projects/${projectId}/tasks`,
      { params }
    );
    return response.data.data;
  },

  // GET /api/tasks/:taskId
  getTask: async (taskId: number) => {
    const response = await apiClient.get<ApiResponse<Task>>(`/tasks/${taskId}`);
    return response.data.data;
  },

  // POST /api/projects/:projectId/tasks
  createTask: async (projectId: number, data: TaskCreateRequest) => {
    const response = await apiClient.post<ApiResponse<Task>>(
      `/projects/${projectId}/tasks`,
      data
    );
    return response.data.data;
  },

  // PATCH /api/tasks/:taskId
  updateTask: async (taskId: number, data: TaskUpdateRequest) => {
    const response = await apiClient.patch<ApiResponse<Task>>(`/tasks/${taskId}`, data);
    return response.data.data;
  },

  // DELETE /api/tasks/:taskId
  deleteTask: async (taskId: number) => {
    await apiClient.delete(`/tasks/${taskId}`);
  },

  // POST /api/tasks/:taskId/status  — 백엔드 필드명: toStatus
  changeStatus: async (taskId: number, toStatus: string) => {
    const response = await apiClient.post<ApiResponse<Task>>(
      `/tasks/${taskId}/status`,
      { toStatus }
    );
    return response.data.data;
  },

  // GET /api/tasks/:taskId/history
  getHistory: async (taskId: number) => {
    const response = await apiClient.get<ApiResponse<TaskHistory[]>>(
      `/tasks/${taskId}/history`
    );
    return response.data.data;
  },

  // GET /api/tasks/:taskId/calendar-sync
  getSyncStatus: async (taskId: number) => {
    const response = await apiClient.get<ApiResponse<CalendarSyncStatus>>(
      `/tasks/${taskId}/calendar-sync`
    );
    return response.data.data;
  },
};
