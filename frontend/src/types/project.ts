// ===== Project 도메인 타입 =====
// 백엔드 ProjectResponse.java 기준

export interface Project {
  id: number;
  name: string;
  createdAt: string;
  updatedAt: string;
}

// 백엔드 CreateProjectRequest.java 기준
export interface ProjectCreateRequest {
  name: string;
}
