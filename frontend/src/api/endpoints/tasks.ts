import apiClient from '../client';
import type { Task, TaskCreateRequest, TaskUpdateRequest } from '@/types/task';

export const tasksApi = {
  getTasks: async (projectId: number) => {
    const response = await apiClient.get<Task[]>(`/projects/${projectId}/tasks`);
    return response.data;
  },

  getTask: async (taskId: number) => {
    const response = await apiClient.get<Task>(`/tasks/${taskId}`);
    return response.data;
  },

  createTask: async (projectId: number, data: TaskCreateRequest) => {
    const response = await apiClient.post<Task>(`/projects/${projectId}/tasks`, data);
    return response.data;
  },

  updateTask: async (taskId: number, data: TaskUpdateRequest) => {
    const response = await apiClient.patch<Task>(`/tasks/${taskId}`, data);
    return response.data;
  },

  deleteTask: async (taskId: number) => {
    await apiClient.delete(`/tasks/${taskId}`);
  },

  changeStatus: async (taskId: number, status: string) => {
    const response = await apiClient.post<Task>(`/tasks/${taskId}/status`, { status });
    return response.data;
  },
};
