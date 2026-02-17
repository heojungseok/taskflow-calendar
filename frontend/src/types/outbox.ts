// ===== Outbox 도메인 타입 =====
// 백엔드 OutboxResponse.java 기준

import type { OutboxStatus, OutboxOpType } from './task';
export type { OutboxStatus, OutboxOpType };

export interface OutboxEntry {
  id: number;
  taskId: number;
  opType: OutboxOpType;
  status: OutboxStatus;
  retryCount: number;
  nextRetryAt: string | null;
  lastError: string | null;
  payload: string;            // JSON string
  createdAt: string;
  updatedAt: string;
}
