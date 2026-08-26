import type {OutboxStatus, TaskStatus} from '@/types/task';

export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  REQUESTED: '요청됨',
  IN_PROGRESS: '진행 중',
  DONE: '완료',
  BLOCKED: '차단됨',
};

export const TASK_ALLOWED_TRANSITIONS: Record<TaskStatus, TaskStatus[]> = {
  REQUESTED: ['IN_PROGRESS', 'BLOCKED'],
  IN_PROGRESS: ['DONE', 'BLOCKED'],
  BLOCKED: ['IN_PROGRESS'],
  DONE: [],
};

export const TASK_CHANGE_TYPE_LABEL: Record<string, string> = {
  STATUS: '상태 변경',
  ASSIGNEE: '담당자 변경',
  SCHEDULE: '일정 변경',
  CONTENT: '내용 변경',
};

export const OUTBOX_STATUS_LABEL: Record<OutboxStatus, string> = {
  PENDING: '대기 중',
  PROCESSING: '처리 중',
  SUCCESS: '성공',
  FAILED: '실패',
  SKIPPED: '건너뜀',
};

export const OUTBOX_BADGE: Record<OutboxStatus, string> = {
  PENDING: 'bg-[var(--st-pending-bg)] text-[var(--st-pending)]',
  PROCESSING: 'bg-[var(--st-running-bg)] text-[var(--st-running)]',
  SUCCESS: 'bg-[var(--st-done-bg)] text-[var(--st-done)]',
  FAILED: 'bg-[var(--st-failed-bg)] text-[var(--st-failed)]',
  SKIPPED: 'bg-[var(--sunken)] text-[var(--ink-3)]',
};

export const formatTaskDateTime = (iso?: string | null) => iso
  ? new Date(iso).toLocaleString('ko-KR', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  : '—';

export const toDateTimeLocal = (iso?: string | null) => iso?.slice(0, 16) ?? '';
