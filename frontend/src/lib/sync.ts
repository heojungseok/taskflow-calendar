import type { CalendarSyncStatus, Task } from '@/types/task';
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

/**
 * Task 목록에 워커가 아직 처리하지 않은 건이 하나라도 있는가.
 *
 * 실패도 포함한다. 워커는 FAILED Outbox를 백오프로 최대 6회 재시도하므로
 * '실패'는 종착점이 아니다. 빼면 재시도가 성공해도 화면에 '실패'가 계속 남는다.
 *
 * ponytail: 재시도가 소진된 최종 실패와 구분하지 못해 그 경우 폴링이 멈추지 않는다.
 *   응답에 retryCount(또는 소진 여부)를 실으면 그때 끊을 수 있다.
 */
const IN_FLIGHT_SYNC_STATES = ['PENDING_SYNC', 'DELETE_PENDING', 'FAILED_SYNC', 'DELETE_FAILED'];

export function hasTaskSyncInFlight(tasks: Task[] | undefined): boolean {
  return !!tasks?.some((t) => t.syncState !== null && IN_FLIGHT_SYNC_STATES.includes(t.syncState));
}

/** 목록에 처리 대기·처리 중인 Outbox가 하나라도 있는가 */
export function hasOutboxInFlight(entries: OutboxEntry[] | undefined): boolean {
  return !!entries?.some((e) => e.status === 'PENDING' || e.status === 'PROCESSING');
}
