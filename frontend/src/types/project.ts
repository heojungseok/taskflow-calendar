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
  cacheStatus: 'LIVE' | 'CACHE_HIT' | 'STALE_FALLBACK';
  totalTaskCount: number;
  syncedTaskCount: number;
  unsyncedTaskCount: number;
  synced: ProjectWeeklySummarySection;
  unsynced: ProjectWeeklySummarySection;
}

export interface ProjectWeeklySummarySection {
  totalTaskCount: number;
  includedTaskCount: number;
  summary: string;
  highlights: string[];
  risks: string[];
  nextActions: string[];
  model: string;
}

export interface ProjectTaskRecommendation {
  projectId: number;
  projectName: string;
  generatedAt: string;
  cacheStatus: 'LIVE' | 'CACHE_HIT';
  totalEligibleTaskCount: number;
  candidateCount: number;
  recommendedCount: number;
  items: ProjectTaskRecommendationItem[];
}

export interface ProjectTaskRecommendationItem {
  taskId: number;
  rank: number;
  score: number;
  title: string;
  status: 'REQUESTED' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED';
  dueAt: string | null;
  calendarSyncEnabled: boolean;
  calendarEventId: string | null;
  syncState: 'SYNCED' | 'PENDING_SYNC' | 'FAILED_SYNC' | 'SYNC_DISABLED' | 'DELETE_PENDING' | 'DELETE_FAILED';
  primaryTag: string;
  secondaryTag: string | null;
  reason: string;
}

// 백엔드 CreateProjectRequest.java 기준
export interface ProjectCreateRequest {
  name: string;
}
