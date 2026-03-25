import apiClient from '../client';
import type { ApiResponse } from '../types';
import type { Project, ProjectCreateRequest, ProjectWeeklySummary } from '@/types/project';

export const projectsApi = {
  // GET /api/projects
  getProjects: async () => {
    const response = await apiClient.get<ApiResponse<Project[]>>('/projects');
    return response.data.data;
  },

  // GET /api/projects/:projectId
  getProject: async (projectId: number) => {
    const response = await apiClient.get<ApiResponse<Project>>(`/projects/${projectId}`);
    return response.data.data;
  },

  // POST /api/projects
  createProject: async (data: ProjectCreateRequest) => {
    const response = await apiClient.post<ApiResponse<Project>>('/projects', data);
    return response.data.data;
  },

  // POST /api/projects/:projectId/weekly-summary
  generateWeeklySummary: async (projectId: number) => {
    const response = await apiClient.post<ApiResponse<ProjectWeeklySummary>>(
      `/projects/${projectId}/weekly-summary`,
      undefined,
      { timeout: 30000 }
    );
    return response.data.data;
  },
};
