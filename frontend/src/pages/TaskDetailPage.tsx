import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { motion, AnimatePresence } from 'framer-motion';
import { Calendar, Clock, ChevronDown, ChevronUp } from 'lucide-react';
import { tasksApi } from '@/api/endpoints/tasks';
import type { TaskStatus, TaskUpdateRequest, OutboxStatus } from '@/types/task';
import { cx, clsx } from '@/styles/cx';

// ── 상수 ──────────────────────────────────────────────────

const STATUS_LABEL: Record<TaskStatus, string> = {
  REQUESTED: '요청됨', IN_PROGRESS: '진행 중', DONE: '완료', BLOCKED: '차단됨',
};

const ALLOWED_TRANSITIONS: Record<TaskStatus, TaskStatus[]> = {
  REQUESTED: ['IN_PROGRESS', 'BLOCKED'], IN_PROGRESS: ['DONE', 'BLOCKED'],
  BLOCKED: ['IN_PROGRESS'], DONE: [],
};

const CHANGE_TYPE_LABEL: Record<string, string> = {
  STATUS: '상태 변경', ASSIGNEE: '담당자 변경', SCHEDULE: '일정 변경', CONTENT: '내용 변경',
};

const OUTBOX_STATUS_LABEL: Record<OutboxStatus, string> = {
  PENDING: '대기 중', PROCESSING: '처리 중', SUCCESS: '성공', FAILED: '실패',
};

const OUTBOX_BADGE: Record<OutboxStatus, string> = {
  PENDING:    'bg-[#1a1a20] text-[#9090a8] border border-[#252530]',
  PROCESSING: 'bg-[#1a2040] text-[#6b8cff] border border-[#2a3558]',
  SUCCESS:    'bg-[#0f2820] text-[#3dd68c] border border-[#1a4030]',
  FAILED:     'bg-[#2a1018] text-[#ff6b6b] border border-[#3d1520]',
};

// ── 유틸 ──────────────────────────────────────────────────

const fmt = (iso?: string | null) => iso
  ? new Date(iso).toLocaleString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  : '—';

const toLocal = (iso?: string | null) => iso?.slice(0, 16) ?? '';

// ── 섹션 컴포넌트 ─────────────────────────────────────────

function Section({ title, children, defaultOpen = true }: { title: string; children: React.ReactNode; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className={cx.card}>
      <button className="w-full flex items-center justify-between" onClick={() => setOpen(v => !v)}>
        <span className={cx.text.subheading}>{title}</span>
        {open ? <ChevronUp size={13} className="text-[#686884]" /> : <ChevronDown size={13} className="text-[#686884]" />}
      </button>
      <AnimatePresence initial={false}>
        {open && (
          <motion.div initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }} transition={{ duration: 0.15 }} className="overflow-hidden">
            <div className="pt-4">{children}</div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

// ── 수정 폼 ───────────────────────────────────────────────

function EditForm({ initialTitle, initialDescription, initialDueAt, initialCalendarSync, onSubmit, onCancel, isPending, isError }: {
  initialTitle: string; initialDescription: string | null; initialDueAt: string | null;
  initialCalendarSync: boolean; onSubmit: (d: TaskUpdateRequest) => void;
  onCancel: () => void; isPending: boolean; isError: boolean;
}) {
  const [title, setTitle] = useState(initialTitle);
  const [desc, setDesc] = useState(initialDescription ?? '');
  const [dueAt, setDueAt] = useState(toLocal(initialDueAt));
  const [calSync, setCalSync] = useState(initialCalendarSync);
  const [titleErr, setTitleErr] = useState('');
  const [dueErr, setDueErr] = useState('');

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    let ok = true;
    if (!title.trim()) { setTitleErr('제목을 입력해주세요.'); ok = false; }
    if (calSync && !dueAt) { setDueErr('캘린더 동기화에는 마감일이 필요합니다.'); ok = false; }
    if (!ok) return;
    onSubmit({ title: title.trim(), description: desc.trim() || undefined, dueAt: dueAt ? `${dueAt}:00` : undefined, calendarSyncEnabled: calSync });
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <div>
        <label className={cx.text.label}>제목</label>
        <input type="text" value={title} onChange={(e) => { setTitle(e.target.value); setTitleErr(''); }} className={clsx(cx.input, titleErr && cx.inputError)} />
        {titleErr && <p className="mt-1 text-[11px] text-[#ff6b6b]">{titleErr}</p>}
      </div>
      <div>
        <label className={cx.text.label}>설명</label>
        <textarea value={desc} onChange={(e) => setDesc(e.target.value)} rows={3} className={cx.textarea} />
      </div>
      <div>
        <label className={cx.text.label}>마감일</label>
        <input type="datetime-local" value={dueAt} onChange={(e) => { setDueAt(e.target.value); setDueErr(''); }} className={clsx(cx.input, dueErr && cx.inputError)} />
        {dueErr && <p className="mt-1 text-[11px] text-[#ff6b6b]">{dueErr}</p>}
      </div>
      <div className="flex items-center gap-2">
        <input type="checkbox" id="editSync" checked={calSync} onChange={(e) => { setCalSync(e.target.checked); setDueErr(''); }} className="w-3.5 h-3.5 rounded accent-[#3b5bff]" />
        <label htmlFor="editSync" className={cx.text.body}>Google Calendar 동기화</label>
      </div>
      {isError && <div className={cx.errorBox}>수정에 실패했습니다.</div>}
      <div className="flex gap-2 pt-1">
        <button type="submit" disabled={isPending} className={cx.btn.primary}>{isPending ? '저장 중...' : '저장'}</button>
        <button type="button" onClick={onCancel} disabled={isPending} className={cx.btn.secondary}>취소</button>
      </div>
    </form>
  );
}

// ── 메인 ──────────────────────────────────────────────────

export default function TaskDetailPage() {
  const { taskId } = useParams<{ taskId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const tid = Number(taskId);
  const [isEditing, setIsEditing] = useState(false);

  const { data: task, isLoading, isError } = useQuery({ queryKey: ['task', tid], queryFn: () => tasksApi.getTask(tid), enabled: !!tid });
  const { data: syncStatus } = useQuery({ queryKey: ['task-sync', tid], queryFn: () => tasksApi.getSyncStatus(tid), enabled: !!tid });
  const { data: history } = useQuery({ queryKey: ['task-history', tid], queryFn: () => tasksApi.getHistory(tid), enabled: !!tid });

  const updateMutation = useMutation({
    mutationFn: (d: TaskUpdateRequest) => tasksApi.updateTask(tid, d),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['task', tid] }); queryClient.invalidateQueries({ queryKey: ['task-sync', tid] }); setIsEditing(false); },
  });

  const changeStatusMutation = useMutation({
    mutationFn: (to: TaskStatus) => tasksApi.changeStatus(tid, to),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['task', tid] }); queryClient.invalidateQueries({ queryKey: ['task-history', tid] }); },
  });

  const deleteMutation = useMutation({
    mutationFn: () => tasksApi.deleteTask(tid),
    onSuccess: () => navigate(`/projects/${task?.projectId}/tasks`),
  });

  if (isLoading) return <div className="flex items-center justify-center h-40"><span className={cx.text.meta}>로딩 중...</span></div>;
  if (isError || !task) return <div className={cx.errorBox}>Task를 불러오지 못했습니다.</div>;

  const next = ALLOWED_TRANSITIONS[task.status];

  return (
    <motion.div className="max-w-2xl mx-auto space-y-2" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.15 }}>

      {/* 뒤로가기 */}
      <button onClick={() => navigate(`/projects/${task.projectId}/tasks`)} className={clsx(cx.btn.ghost, 'flex items-center gap-1 mb-3')}>
        ← 목록
      </button>

      {/* 기본 정보 */}
      <Section title="기본 정보">
        {!isEditing ? (
          <div>
            <div className="flex items-start justify-between mb-4">
              <div>
                <h2 className="text-[15px] font-semibold text-[#e8e8ed] leading-snug mb-2">{task.title}</h2>
                <motion.span key={task.status} initial={{ opacity: 0, scale: 0.9 }} animate={{ opacity: 1, scale: 1 }} transition={{ duration: 0.15 }}
                  className={clsx(cx.badge.base, cx.badge[task.status])}>
                  {STATUS_LABEL[task.status]}
                </motion.span>
              </div>
              <div className="flex gap-1.5 flex-shrink-0 ml-4">
                <button onClick={() => setIsEditing(true)} className={cx.btn.secondary}>수정</button>
                <button onClick={() => { if (!window.confirm('삭제하시겠습니까?')) return; deleteMutation.mutate(); }}
                  disabled={deleteMutation.isPending}
                  className="px-3 py-1.5 text-xs font-medium border border-[#2a1018] text-[#ff6b6b]/60 hover:text-[#ff6b6b] hover:border-[#3d1520] rounded transition-all duration-150 disabled:opacity-40">
                  삭제
                </button>
              </div>
            </div>

            {task.description && (
              <p className={clsx(cx.text.body, 'whitespace-pre-wrap mb-4 leading-relaxed')}>{task.description}</p>
            )}

            <div className="grid grid-cols-2 gap-3 mb-4">
              <div>
                <p className={clsx(cx.text.meta, 'mb-1 flex items-center gap-1')}><Clock size={10} /> 마감일</p>
                <p className={cx.text.body}>{fmt(task.dueAt)}</p>
              </div>
              <div>
                <p className={clsx(cx.text.meta, 'mb-1')}>생성일</p>
                <p className={cx.text.body}>{fmt(task.createdAt)}</p>
              </div>
            </div>

            {next.length > 0 && (
              <div className={clsx(cx.divider, 'pt-3')}>
                <p className={clsx(cx.text.meta, 'mb-2')}>상태 변경</p>
                <div className="flex gap-1.5">
                  {next.map((s) => (
                    <button key={s} onClick={() => changeStatusMutation.mutate(s)} disabled={changeStatusMutation.isPending} className={cx.btn.statusTransition}>
                      → {STATUS_LABEL[s]}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        ) : (
          <EditForm
            initialTitle={task.title} initialDescription={task.description}
            initialDueAt={task.dueAt} initialCalendarSync={task.calendarSyncEnabled}
            onSubmit={(d) => updateMutation.mutate(d)} onCancel={() => setIsEditing(false)}
            isPending={updateMutation.isPending} isError={updateMutation.isError}
          />
        )}
      </Section>

      {/* 캘린더 동기화 */}
      <Section title="캘린더 동기화" defaultOpen={task.calendarSyncEnabled}>
        {!task.calendarSyncEnabled ? (
          <p className={cx.text.meta}>동기화 비활성화 상태입니다.</p>
        ) : (
          <div className="space-y-3">
            <div className="flex items-center gap-4">
              <p className={clsx(cx.text.meta, 'w-20')}>상태</p>
              {syncStatus?.lastOutboxStatus
                ? <span className={clsx(cx.badge.base, OUTBOX_BADGE[syncStatus.lastOutboxStatus])}>{OUTBOX_STATUS_LABEL[syncStatus.lastOutboxStatus]}</span>
                : <span className={cx.text.meta}>—</span>}
            </div>
            <div className="flex items-center gap-4">
              <p className={clsx(cx.text.meta, 'w-20 flex items-center gap-1')}><Calendar size={10} /> 이벤트</p>
              <p className={cx.text.body}>
                {task.calendarEventId
                  ? <span className="text-[#3dd68c]">연동됨 ({task.calendarEventId.slice(0, 12)}...)</span>
                  : <span className="text-[#8080a0]">미연동</span>}
              </p>
            </div>
            <div className="flex items-center gap-4">
              <p className={clsx(cx.text.meta, 'w-20')}>마지막 성공</p>
              <p className={cx.text.body}>{fmt(syncStatus?.lastSyncedAt)}</p>
            </div>
            {syncStatus?.lastOutboxStatus === 'FAILED' && syncStatus.lastOutboxError && (
              <div className={clsx(cx.errorBox, 'mt-2')}>
                <p className="font-medium mb-1">오류</p>
                <p className="font-mono break-all text-[11px]">{syncStatus.lastOutboxError}</p>
              </div>
            )}
          </div>
        )}
      </Section>

      {/* 변경 이력 */}
      <Section title="변경 이력" defaultOpen={false}>
        {!history || history.length === 0 ? (
          <p className={cx.text.meta}>변경 이력이 없습니다.</p>
        ) : (
          <div className="space-y-3">
            {history.map((h, i) => (
              <div key={i} className={clsx(i > 0 && clsx(cx.divider, 'pt-3'))}>
                <div className="flex items-center justify-between mb-1.5">
                  <span className={cx.text.body}>{CHANGE_TYPE_LABEL[h.changeType] ?? h.changeType}</span>
                  <span className={cx.text.meta}>{fmt(h.createdAt)}</span>
                </div>
                {(h.beforeValue || h.afterValue) && (
                  <div className="flex items-center gap-2 mb-1.5 font-mono">
                    <span className="flex-1 px-2 py-1 rounded text-[11px] bg-[#0d0d14] text-[#8080a0] break-all border border-[#1a1a24]">{h.beforeValue ?? '—'}</span>
                    <span className="text-[#5a5a7a] flex-shrink-0 text-xs">→</span>
                    <span className="flex-1 px-2 py-1 rounded text-[11px] bg-[#0d1020] text-[#8aabff] break-all border border-[#1a2040]">{h.afterValue ?? '—'}</span>
                  </div>
                )}
                <p className={cx.text.meta}>{h.changedByUserName}</p>
              </div>
            ))}
          </div>
        )}
      </Section>
    </motion.div>
  );
}
