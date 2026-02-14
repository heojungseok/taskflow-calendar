export interface Task {
  id: number;
  projectId: number;
  title: string;
  description: string | null;
  status: TaskStatus;
  dueAt: string | null;
  calendarSyncEnabled: boolean;
  calendarEventId: string | null;
  assigneeUserId: number | null;
  createdAt: string;
  updatedAt: string;
  deleted: boolean;
}

export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'ARCHIVED';

export interface TaskCreateRequest {
  title: string;
  description?: string;
  dueAt?: string;
  calendarSyncEnabled?: boolean;
  assigneeUserId?: number;
}

export interface TaskUpdateRequest {
  title?: string;
  description?: string;
  dueAt?: string;
  calendarSyncEnabled?: boolean;
  assigneeUserId?: number;
}
