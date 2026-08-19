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

export interface ProjectTaskSearchResponse {
  query: string;
  /** 의도 파싱이 약해 결과 없이 추천 질의만 온 경우. 의미 검색 가용성과는 무관하다. */
  intentFallback: boolean;
  /** UNAVAILABLE이면 의미 검색이 죽어서 어휘 검색만으로 만든 결과다. */
  semanticStatus: 'READY' | 'DISABLED' | 'UNAVAILABLE';
  intent: ProjectTaskSearchIntent;
  taskResults: ProjectTaskSearchResultItem[];
  relatedProjects: RelatedProjectSearchResult[];
  suggestedQueries: string[];
}

export interface ProjectTaskSearchIntent {
  rawQuery: string;
  queryType: 'TOPIC_SEARCH' | 'RELATIONAL_SEARCH' | 'BROAD_SEARCH';
  targetType: 'TASK' | 'PROJECT' | 'MIXED';
  domainType: 'WORK' | 'PERSONAL' | 'LIFE' | 'MIXED' | 'UNKNOWN';
  mainAction: 'PREPARE' | 'SUBMIT' | 'BUY' | 'VISIT' | 'MEET' | 'ORGANIZE' | 'FIX' | 'CHECK' | 'UNKNOWN';
  secondaryActions: Array<'PREPARE' | 'SUBMIT' | 'BUY' | 'VISIT' | 'MEET' | 'ORGANIZE' | 'FIX' | 'CHECK'>;
  topicTerms: string[];
  participantTerms: string[];
  locationTerms: string[];
  timeIntent: 'TODAY' | 'THIS_WEEK' | 'THIS_MONTH' | 'UPCOMING' | 'RECENT' | 'OVERDUE' | 'DEFERRED' | 'UNSPECIFIED';
  priorityIntent: 'URGENT' | 'IMPORTANT' | 'MUST_DO' | 'DEFERRED' | 'NONE';
  statusIntents: Array<'REQUESTED' | 'IN_PROGRESS' | 'BLOCKED' | 'DONE'>;
  syncIntent: 'SYNCED' | 'UNSYNCED' | 'FAILED' | 'ANY';
  relationPolicy: 'PREFER_ALL' | 'ALLOW_PARTIAL';
  overallConfidence: number;
  fieldConfidence: Record<string, number>;
}

export interface ProjectTaskSearchResultItem {
  taskId: number;
  projectId: number;
  projectName: string;
  title: string;
  status: 'REQUESTED' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED';
  dueAt: string | null;
  calendarSyncEnabled: boolean;
  calendarEventId: string | null;
  syncState: 'SYNCED' | 'PENDING_SYNC' | 'FAILED_SYNC' | 'SYNC_DISABLED' | 'DELETE_PENDING' | 'DELETE_FAILED';
  score: number;
}

export interface RelatedProjectSearchResult {
  projectId: number;
  projectName: string;
  matchedTaskCount: number;
  score: number;
}

// 백엔드 CreateProjectRequest.java 기준
export interface ProjectCreateRequest {
  name: string;
}
