import apiClient from '../client';

export interface HealthResponse {
  status: string;
}

export const healthApi = {
  check: async (): Promise<HealthResponse> => {
    const response = await apiClient.get<HealthResponse>('/actuator/health');
    return response.data;
  },
};
