// ===== Project 도메인 타입 =====
// 백엔드 ProjectResponse.java 기준

export interface Project {
  id: number;
  name: string;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectWeeklySummary {
  projectId: number;
  projectName: string;
  weekStart: string;
  weekEnd: string;
  generatedAt: string;
  totalTaskCount: number;
  includedTaskCount: number;
  summary: string;
  highlights: string[];
  risks: string[];
  nextActions: string[];
  model: string;
}

// 백엔드 CreateProjectRequest.java 기준
export interface ProjectCreateRequest {
  name: string;
}
