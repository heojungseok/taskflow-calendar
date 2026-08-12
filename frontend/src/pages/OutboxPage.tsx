import { useState } from 'react';
import { useQuery, useMutation } from '@tanstack/react-query';
import { motion, AnimatePresence } from 'framer-motion';
import { ChevronDown, ChevronUp, Play } from 'lucide-react';
import { outboxApi } from '@/api/endpoints/calendar';
import type { OutboxEntry, OutboxStatus, OutboxOpType } from '@/types/outbox';
import { cx, clsx } from '@/styles/cx';

// ── 상수 ──────────────────────────────────────────────────

const STATUS_LABEL: Record<OutboxStatus, string> = {
  PENDING: '대기', PROCESSING: '처리 중', SUCCESS: '성공', FAILED: '실패',
};

/** 색이 붙는 자리 — 기계의 상태 */
const STATUS_MARK: Record<OutboxStatus, string> = {
  PENDING: 'var(--st-pending-mark)',
  PROCESSING: 'var(--st-running-mark)',
  SUCCESS: 'var(--st-done-mark)',
  FAILED: 'var(--st-failed-mark)',
};

/** 연산 종류는 상태가 아니므로 색을 주지 않는다 */
const OP_LABEL: Record<OutboxOpType, string> = {
  UPSERT: '반영',
  DELETE: '삭제',
};

const STATUS_FILTERS = [
  { label: '전체', value: '' }, { label: '대기', value: 'PENDING' },
  { label: '처리 중', value: 'PROCESSING' }, { label: '성공', value: 'SUCCESS' },
  { label: '실패', value: 'FAILED' },
];

const fmt = (iso?: string | null) => iso
  ? new Date(iso).toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
  : '—';

/** 상태 표시등 — 로그인 화면의 파이프라인과 같은 어휘 */
function StatusMark({ status, count }: { status: OutboxStatus; count?: number }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-[13px] text-[var(--ink-2)] whitespace-nowrap">
      <span
        aria-hidden
        className={clsx(
          'w-[7px] h-[7px] rounded-[1px] shrink-0',
          status === 'PROCESSING' && 'running-dot'
        )}
        style={{ backgroundColor: STATUS_MARK[status] }}
      />
      {STATUS_LABEL[status]}
      {count !== undefined && (
        <span className="font-mono text-[12px] text-[var(--ink-3)] tabular">{count}</span>
      )}
    </span>
  );
}

// ── Outbox 행 ─────────────────────────────────────────────

function OutboxRow({ entry }: { entry: OutboxEntry }) {
  const [open, setOpen] = useState(false);
  let payload = entry.payload;
  try { payload = JSON.stringify(JSON.parse(entry.payload), null, 2); } catch { /* 원본 유지 */ }

  return (
    <li className="border-b border-[var(--rule)]">
      <button
        type="button"
        aria-expanded={open}
        className="w-full flex items-center gap-3 px-2 -mx-2 py-3 text-left hover:bg-[var(--sunken)] transition-colors duration-150"
        onClick={() => setOpen(v => !v)}
      >
        <span className={clsx(cx.text.data, 'shrink-0 w-12')}>#{entry.id}</span>
        <span className="text-[13px] text-[var(--ink-3)] shrink-0 w-8">{OP_LABEL[entry.opType]}</span>
        <span className="shrink-0 w-20"><StatusMark status={entry.status} /></span>

        <span className="text-[14px] text-[var(--ink)] shrink-0">Task&nbsp;#{entry.taskId}</span>

        {entry.retryCount > 0 && (
          <span className={cx.text.data}>재시도 {entry.retryCount}회</span>
        )}

        <span className={clsx(cx.text.data, 'ml-auto shrink-0')}>{fmt(entry.createdAt)}</span>
        <span aria-hidden className="text-[var(--ink-3)] shrink-0">
          {open ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
        </span>
      </button>

      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.14 }}
            className="overflow-hidden"
          >
            <div className="pb-5 pt-1 space-y-4">
              {entry.lastError && (
                <div>
                  <p className={cx.text.label}>오류</p>
                  <p className="font-mono text-[12px] leading-5 break-all px-3 py-2 rounded-[var(--radius)] bg-[var(--st-failed-bg)] text-[var(--st-failed)]">
                    {entry.lastError}
                  </p>
                </div>
              )}

              {entry.nextRetryAt && (
                <p className="text-[13px] text-[var(--ink-2)]">
                  다음 재시도 <span className={cx.text.data}>{fmt(entry.nextRetryAt)}</span>
                </p>
              )}

              <div>
                <p className={cx.text.label}>보낼 내용</p>
                <pre className="font-mono text-[12px] leading-5 whitespace-pre-wrap break-all px-3 py-2.5 rounded-[var(--radius)] bg-[var(--sunken)] border border-[var(--rule)] text-[var(--ink-2)] overflow-x-auto">
                  {payload}
                </pre>
              </div>

              <p className="text-[13px] text-[var(--ink-3)]">
                마지막 수정 <span className={cx.text.data}>{fmt(entry.updatedAt)}</span>
              </p>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </li>
  );
}

// ── 메인 ──────────────────────────────────────────────────

export default function OutboxPage() {
  const [statusFilter, setStatusFilter] = useState('');
  const [taskIdInput, setTaskIdInput] = useState('');
  const [taskIdFilter, setTaskIdFilter] = useState<number | undefined>();
  const [msg, setMsg] = useState('');

  const { data: entries, isLoading, isError, refetch } = useQuery({
    queryKey: ['outbox', statusFilter, taskIdFilter],
    queryFn: () => outboxApi.getOutboxList({ status: statusFilter || undefined, taskId: taskIdFilter }),
  });

  const triggerMutation = useMutation({
    mutationFn: () => outboxApi.triggerWorker(),
    onSuccess: () => { setMsg('밀린 항목을 처리했습니다.'); refetch(); setTimeout(() => setMsg(''), 3000); },
    onError: () => { setMsg('처리하지 못했습니다. 잠시 후 다시 시도하세요.'); setTimeout(() => setMsg(''), 3000); },
  });

  const counts = entries?.reduce((a, e) => ({ ...a, [e.status]: (a[e.status] ?? 0) + 1 }), {} as Record<string, number>);

  return (
    <div>
      {/* 제목 */}
      <div className="flex items-center justify-between gap-4 mb-2">
        <div className="flex items-center gap-2.5">
          <h2 className={cx.text.heading}>동기화 현황</h2>
          {entries && (
            <span className="font-mono text-[12px] text-[var(--ink-3)] bg-[var(--sunken)] border border-[var(--rule)] px-1.5 py-0.5 rounded-[var(--radius)] tabular">
              {entries.length}
            </span>
          )}
        </div>
        <button
          onClick={() => triggerMutation.mutate()}
          disabled={triggerMutation.isPending}
          className={clsx(cx.btn.secondary, 'inline-flex items-center gap-1.5 shrink-0 whitespace-nowrap')}
        >
          <Play size={12} strokeWidth={2.5} />
          {triggerMutation.isPending ? '처리 중' : '지금 처리'}
        </button>
      </div>

      <p className={clsx(cx.text.meta, 'mb-6')}>
        캘린더로 보낼 항목이 여기에 쌓입니다. 실패한 항목은 지수 백오프로 다시 시도합니다.
      </p>

      {/* 처리 결과 */}
      <AnimatePresence>
        {msg && (
          <motion.p
            role="status"
            initial={{ opacity: 0, y: -4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            className="mb-4 text-[13px] text-[var(--ink-2)]"
          >
            {msg}
          </motion.p>
        )}
      </AnimatePresence>

      {/* 상태 집계 — 이 화면의 요약 */}
      {counts && Object.keys(counts).length > 0 && (
        <div className="flex flex-wrap gap-x-6 gap-y-2 mb-6 pb-5 border-b border-[var(--rule)]">
          {(Object.entries(counts) as [OutboxStatus, number][]).map(([s, n]) => (
            <StatusMark key={s} status={s} count={n} />
          ))}
        </div>
      )}

      {/* 필터 */}
      <div className="flex items-end justify-between gap-4 mb-6 flex-wrap border-b border-[var(--rule)]">
        <div className="flex gap-5">
          {STATUS_FILTERS.map((f) => (
            <button
              key={f.value}
              onClick={() => setStatusFilter(f.value)}
              className={clsx(cx.btn.filter, statusFilter === f.value ? cx.btn.filterActive : cx.btn.filterInactive)}
            >
              {f.label}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 pb-2">
          <input
            type="number"
            value={taskIdInput}
            onChange={(e) => setTaskIdInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && setTaskIdFilter(parseInt(taskIdInput, 10) || undefined)}
            placeholder="Task 번호"
            className={clsx(cx.input, 'w-28 py-1.5')}
          />
          <button
            onClick={() => setTaskIdFilter(parseInt(taskIdInput, 10) || undefined)}
            className={clsx(cx.btn.secondary, 'shrink-0')}
          >
            찾기
          </button>
          {taskIdFilter && (
            <button
              onClick={() => { setTaskIdInput(''); setTaskIdFilter(undefined); }}
              className={clsx(cx.btn.ghost, 'shrink-0')}
              aria-label="Task 번호 필터 해제"
            >
              해제
            </button>
          )}
        </div>
      </div>

      {/* 목록 */}
      {isLoading ? (
        <p className={cx.text.meta}>불러오는 중</p>
      ) : isError ? (
        <div className={cx.errorBox}>목록을 불러오지 못했습니다.</div>
      ) : !entries?.length ? (
        <div className={cx.emptyState}>
          <p className="text-[15px] text-[var(--ink)] mb-1">보낼 항목이 없습니다.</p>
          <p className="text-[13px] text-[var(--ink-2)]">
            모든 일정이 캘린더에 반영된 상태입니다.
          </p>
        </div>
      ) : (
        <ul className="border-t border-[var(--rule)]">
          <AnimatePresence initial={false}>
            {entries.map((e) => (
              <motion.div
                key={e.id}
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.14 }}
              >
                <OutboxRow entry={e} />
              </motion.div>
            ))}
          </AnimatePresence>
        </ul>
      )}
    </div>
  );
}
