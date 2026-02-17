// ===== Task 도메인 타입 =====
// 백엔드 TaskResponse.java 기준

export type TaskStatus = 'REQUESTED' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED';

export interface Task {
  id: number;
  projectId: number;
  title: string;
  description: string | null;
  status: TaskStatus;
  assigneeUserId: number | null;
  assigneeName: string | null;
  startAt: string | null;      // ISO-8601 LocalDateTime: "yyyy-MM-dd'T'HH:mm:ss"
  dueAt: string | null;        // ISO-8601 LocalDateTime: "yyyy-MM-dd'T'HH:mm:ss"
  calendarSyncEnabled: boolean;
  calendarEventId: string | null;
  createdAt: string;
  updatedAt: string;
}

// 백엔드 CreateTaskRequest.java 기준
export interface TaskCreateRequest {
  title: string;
  description?: string;
  assigneeUserId?: number;
  startAt?: string;
  dueAt?: string;
  calendarSyncEnabled?: boolean;
}

// 백엔드 UpdateTaskRequest.java 기준 (모든 필드 optional)
export interface TaskUpdateRequest {
  title?: string;
  description?: string;
  assigneeUserId?: number;
  startAt?: string;
  dueAt?: string;
  calendarSyncEnabled?: boolean;
}

// 백엔드 ChangeTaskStatusRequest.java 기준 (필드명: toStatus)
export interface ChangeTaskStatusRequest {
  toStatus: TaskStatus;
}

// ===== TaskHistory 타입 =====
// 백엔드 TaskHistoryResponse.java 기준

export type TaskChangeType = 'STATUS' | 'ASSIGNEE' | 'SCHEDULE' | 'CONTENT';

export interface TaskHistory {
  changeType: TaskChangeType;
  beforeValue: string | null;
  afterValue: string | null;
  changedByUserName: string;  // 백엔드 필드명: changedByUserName
  createdAt: string;
}

// ===== CalendarSyncStatus 타입 =====
// 백엔드 CalendarSyncStatusResponse.java 기준

export type OutboxStatus = 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED';
export type OutboxOpType = 'UPSERT' | 'DELETE';

export interface CalendarSyncStatus {
  taskId: number;
  calendarSyncEnabled: boolean;
  calendarEventId: string | null;
  lastOutboxStatus: OutboxStatus | null;
  lastOutboxOpType: OutboxOpType | null;
  lastOutboxError: string | null;
  lastSyncedAt: string | null;
  lastOutboxCreatedAt: string | null;
}
