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

const STATUS_BADGE: Record<OutboxStatus, string> = {
  PENDING:    'bg-[#1a1a20] text-[#6b6b80] border border-[#252530]',
  PROCESSING: 'bg-[#1a2040] text-[#6b8cff] border border-[#2a3558]',
  SUCCESS:    'bg-[#0f2820] text-[#3dd68c] border border-[#1a4030]',
  FAILED:     'bg-[#2a1018] text-[#ff6b6b] border border-[#3d1520]',
};

const OP_BADGE: Record<OutboxOpType, string> = {
  UPSERT: 'bg-[#0d1020] text-[#6b8cff] border border-[#1a2040]',
  DELETE: 'bg-[#1a0d10] text-[#ff6b6b]/70 border border-[#2a1018]',
};

const STATUS_FILTERS = [
  { label: '전체', value: '' }, { label: '대기', value: 'PENDING' },
  { label: '처리 중', value: 'PROCESSING' }, { label: '성공', value: 'SUCCESS' },
  { label: '실패', value: 'FAILED' },
];

const fmt = (iso?: string | null) => iso
  ? new Date(iso).toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
  : '—';

// ── Outbox 행 ─────────────────────────────────────────────

function OutboxRow({ entry }: { entry: OutboxEntry }) {
  const [open, setOpen] = useState(false);
  let payload = entry.payload;
  try { payload = JSON.stringify(JSON.parse(entry.payload), null, 2); } catch { /* 원본 유지 */ }

  return (
    <div className={clsx(cx.card, 'p-0 overflow-hidden')}>
      <div
        className="flex items-center gap-2.5 px-4 py-2.5 cursor-pointer hover:bg-[#13131c] transition-colors duration-100"
        onClick={() => setOpen(v => !v)}
      >
        <span className={cx.text.meta}>#{entry.id}</span>

        <span className={clsx(cx.badge.base, OP_BADGE[entry.opType])}>{entry.opType}</span>
        <span className={clsx(cx.badge.base, STATUS_BADGE[entry.status])}>{STATUS_LABEL[entry.status]}</span>

        <span className={cx.text.body}>Task&nbsp;#{entry.taskId}</span>
        <span className={cx.text.meta}>retry&nbsp;{entry.retryCount}</span>

        <span className={clsx(cx.text.meta, 'ml-auto')}>{fmt(entry.createdAt)}</span>
        <span className="text-[#2a2a3a]">{open ? <ChevronUp size={12} /> : <ChevronDown size={12} />}</span>
      </div>

      <AnimatePresence initial={false}>
        {open && (
          <motion.div initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }} transition={{ duration: 0.12 }} className="overflow-hidden">
            <div className="px-4 pb-4 pt-3 bg-[#0d0d14] space-y-3 border-t border-[#1a1a24]">
              {entry.lastError && (
                <div>
                  <p className="text-[11px] font-medium text-[#ff6b6b] mb-1">오류</p>
                  <p className="text-[11px] font-mono break-all px-2 py-1.5 rounded bg-[#2a1018] text-[#ff6b6b]/80 border border-[#3d1520]">{entry.lastError}</p>
                </div>
              )}
              {entry.nextRetryAt && (
                <p className={cx.text.meta}>다음 재시도: <span className={cx.text.body}>{fmt(entry.nextRetryAt)}</span></p>
              )}
              <div>
                <p className={clsx(cx.text.meta, 'mb-1.5')}>Payload</p>
                <pre className="text-[11px] font-mono whitespace-pre-wrap break-all px-3 py-2.5 rounded bg-[#0a0a0f] border border-[#1a1a24] text-[#5a5a72] overflow-x-auto">
                  {payload}
                </pre>
              </div>
              <p className={cx.text.meta}>수정: {fmt(entry.updatedAt)}</p>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
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
    onSuccess: () => { setMsg('Worker 실행 완료.'); refetch(); setTimeout(() => setMsg(''), 3000); },
    onError: () => { setMsg('Worker 실행 실패.'); setTimeout(() => setMsg(''), 3000); },
  });

  const counts = entries?.reduce((a, e) => ({ ...a, [e.status]: (a[e.status] ?? 0) + 1 }), {} as Record<string, number>);

  return (
    <div>
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-5">
        <div className="flex items-center gap-2.5">
          <h2 className={cx.text.heading}>Outbox</h2>
          {entries && (
            <span className="text-[11px] text-[#a0a0bc] bg-[#111118] border border-[#252535] px-1.5 py-0.5 rounded-[3px]">
              {entries.length}
            </span>
          )}
        </div>
        <button onClick={() => triggerMutation.mutate()} disabled={triggerMutation.isPending}
          className={clsx(cx.btn.secondary, 'flex items-center gap-1.5')}>
          <Play size={11} strokeWidth={2.5} />
          {triggerMutation.isPending ? '실행 중...' : 'Worker 실행'}
        </button>
      </div>

      {/* Worker 피드백 */}
      <AnimatePresence>
        {msg && (
          <motion.div initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}
            className="mb-4 px-3 py-2 rounded text-xs bg-[#0f2820] border border-[#1a4030] text-[#3dd68c]">
            {msg}
          </motion.div>
        )}
      </AnimatePresence>

      {/* 상태 카운트 */}
      {counts && Object.keys(counts).length > 0 && (
        <div className="flex gap-1.5 mb-4 flex-wrap">
          {(Object.entries(counts) as [OutboxStatus, number][]).map(([s, n]) => (
            <span key={s} className={clsx(cx.badge.base, STATUS_BADGE[s])}>{STATUS_LABEL[s]} {n}</span>
          ))}
        </div>
      )}

      {/* 필터 */}
      <div className="flex items-center gap-3 mb-4 flex-wrap">
        <div className="flex gap-1">
          {STATUS_FILTERS.map((f) => (
            <button key={f.value} onClick={() => setStatusFilter(f.value)}
              className={clsx(cx.btn.filter, statusFilter === f.value ? cx.btn.filterActive : cx.btn.filterInactive)}>
              {f.label}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1 ml-auto">
          <input type="number" value={taskIdInput} onChange={(e) => setTaskIdInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && setTaskIdFilter(parseInt(taskIdInput, 10) || undefined)}
            placeholder="Task ID" className={clsx(cx.input, 'w-24 py-1')} />
          <button onClick={() => setTaskIdFilter(parseInt(taskIdInput, 10) || undefined)} className={cx.btn.secondary}>검색</button>
          {taskIdFilter && <button onClick={() => { setTaskIdInput(''); setTaskIdFilter(undefined); }} className={cx.btn.ghost}>✕</button>}
        </div>
      </div>

      {/* 목록 */}
      {isLoading ? (
        <div className="flex items-center justify-center h-40"><span className={cx.text.meta}>로딩 중...</span></div>
      ) : isError ? (
        <div className={cx.errorBox}>목록을 불러오지 못했습니다.</div>
      ) : !entries?.length ? (
        <div className={cx.emptyState}><p className="text-[13px]">Outbox 항목이 없습니다.</p></div>
      ) : (
        <div className="space-y-1.5">
          <AnimatePresence>
            {entries.map((e) => (
              <motion.div key={e.id} initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: 0.12 }}>
                <OutboxRow entry={e} />
              </motion.div>
            ))}
          </AnimatePresence>
        </div>
      )}
    </div>
  );
}
