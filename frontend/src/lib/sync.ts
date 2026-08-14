import type { CalendarSyncStatus } from '@/types/task';
import type { OutboxEntry } from '@/types/outbox';

/**
 * 동기화 상태 폴링 간격 (ms)
 *
 * 서버 워커는 `outbox.worker.fixed-delay`(기본 60초)마다 Outbox를 처리한다.
 * 그 변화를 화면이 제때 받으려면 워커 주기보다 촘촘해야 한다.
 * 처리할 항목이 없으면 각 화면에서 폴링을 멈춘다.
 */
export const SYNC_POLL_INTERVAL_MS = 10_000;

/** 워커가 아직 손댈 것이 남은 상태인가 */
export function isSyncInFlight(status: CalendarSyncStatus | undefined): boolean {
  const last = status?.lastOutboxStatus;
  return last === 'PENDING' || last === 'PROCESSING';
}

/** 목록에 처리 대기·처리 중인 Outbox가 하나라도 있는가 */
export function hasOutboxInFlight(entries: OutboxEntry[] | undefined): boolean {
  return !!entries?.some((e) => e.status === 'PENDING' || e.status === 'PROCESSING');
}
