import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { motion, AnimatePresence } from 'framer-motion';
import { Plus, Calendar, Clock, Sparkles } from 'lucide-react';
import { tasksApi } from '@/api/endpoints/tasks';
import { projectsApi } from '@/api/endpoints/projects';
import type { Task, TaskStatus, TaskCreateRequest } from '@/types/task';
import type { ProjectWeeklySummary } from '@/types/project';
import { cx, clsx } from '@/styles/cx';

// ── 상수 ──────────────────────────────────────────────────

const STATUS_LABEL: Record<TaskStatus, string> = {
  REQUESTED: '요청됨',
  IN_PROGRESS: '진행 중',
  DONE: '완료',
  BLOCKED: '차단됨',
};

const ALLOWED_TRANSITIONS: Record<TaskStatus, TaskStatus[]> = {
  REQUESTED:   ['IN_PROGRESS', 'BLOCKED'],
  IN_PROGRESS: ['DONE', 'BLOCKED'],
  BLOCKED:     ['IN_PROGRESS'],
  DONE:        [],
};

const STATUS_FILTERS = [
  { label: '전체',     value: '' },
  { label: '요청됨',   value: 'REQUESTED' },
  { label: '진행 중',  value: 'IN_PROGRESS' },
  { label: '완료',     value: 'DONE' },
  { label: '차단됨',   value: 'BLOCKED' },
];

// ── Task 카드 ─────────────────────────────────────────────

interface TaskCardProps {
  task: Task;
  onStatusChange: (id: number, to: TaskStatus) => void;
  onDelete: (id: number) => void;
  onClickDetail: (id: number) => void;
  isChanging: boolean;
  isDeleting: boolean;
}

function TaskCard({ task, onStatusChange, onDelete, onClickDetail, isChanging, isDeleting }: TaskCardProps) {
  const next = ALLOWED_TRANSITIONS[task.status];

  return (
    <div className={cx.card}>
      {/* 클릭 영역 */}
      <div className="cursor-pointer group mb-3" onClick={() => onClickDetail(task.id)}>
        <p className={clsx(cx.text.cardTitle, 'mb-2.5 leading-snug group-hover:text-[#e8e8ed] transition-colors duration-150')}>
          {task.title}
        </p>

        {/* 배지 행 */}
        <div className="flex items-center gap-1.5 flex-wrap">
          <motion.span
            key={task.status}
            initial={{ opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 0.15 }}
            className={clsx(cx.badge.base, cx.badge[task.status])}
          >
            {STATUS_LABEL[task.status]}
          </motion.span>

          {task.calendarSyncEnabled && (
            <span className={clsx(
              cx.badge.base,
              task.calendarEventId
                ? 'bg-[#1a1530] text-[#a78bfa] border border-[#2d2050]'
                : 'bg-[#1a1a20] text-[#8080a0] border border-[#252530]',
            )}>
              <Calendar size={9} strokeWidth={2} />
              {task.calendarEventId ? '동기화' : '대기'}
            </span>
          )}
        </div>

        {task.dueAt && (
          <p className={clsx(cx.text.meta, 'mt-2 flex items-center gap-1')}>
            <Clock size={10} strokeWidth={2} />
            {new Date(task.dueAt).toLocaleDateString('ko-KR')}
          </p>
        )}
      </div>

      {/* 하단 액션 */}
      <div className={clsx(cx.divider, 'pt-2.5 flex items-center justify-between')}>
        <div className="flex gap-1">
          {next.map((s) => (
            <button
              key={s}
              onClick={() => onStatusChange(task.id, s)}
              disabled={isChanging}
              className={cx.btn.statusTransition}
            >
              {STATUS_LABEL[s]}
            </button>
          ))}
          {next.length === 0 && <span className={cx.text.meta}>완료됨</span>}
        </div>
        <button onClick={() => onDelete(task.id)} disabled={isDeleting} className={cx.btn.danger}>
          삭제
        </button>
      </div>
    </div>
  );
}

// ── 생성 모달 ─────────────────────────────────────────────

interface CreateModalProps {
  onClose: () => void;
  onSubmit: (d: TaskCreateRequest) => void;
  isPending: boolean;
  isError: boolean;
}

function CreateModal({ onClose, onSubmit, isPending, isError }: CreateModalProps) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [dueAt, setDueAt] = useState('');
  const [calendarSync, setCalendarSync] = useState(false);
  const [titleErr, setTitleErr] = useState('');
  const [dueErr, setDueErr] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    let ok = true;
    if (!title.trim()) { setTitleErr('제목을 입력해주세요.'); ok = false; }
    if (calendarSync && !dueAt) { setDueErr('캘린더 동기화에는 마감일이 필요합니다.'); ok = false; }
    if (!ok) return;
    onSubmit({
      title: title.trim(),
      description: description.trim() || undefined,
      dueAt: dueAt ? `${dueAt}:00` : undefined,
      calendarSyncEnabled: calendarSync || undefined,
    });
  };

  return (
    <motion.div className={cx.overlay} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.12 }}>
      <motion.div className={cx.modal} initial={{ opacity: 0, scale: 0.97, y: 6 }} animate={{ opacity: 1, scale: 1, y: 0 }} exit={{ opacity: 0, scale: 0.97 }} transition={{ duration: 0.12 }}>
        <h3 className={clsx(cx.text.subheading, 'mb-5')}>새 Task</h3>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className={cx.text.label}>제목</label>
            <input type="text" value={title} onChange={(e) => { setTitle(e.target.value); setTitleErr(''); }}
              placeholder="Task 제목" className={clsx(cx.input, titleErr && cx.inputError)} autoFocus />
            {titleErr && <p className="mt-1 text-[11px] text-[#ff6b6b]">{titleErr}</p>}
          </div>
          <div>
            <label className={cx.text.label}>설명</label>
            <textarea value={description} onChange={(e) => setDescription(e.target.value)}
              placeholder="선택 사항" rows={2} className={cx.textarea} />
          </div>
          <div>
            <label className={cx.text.label}>마감일</label>
            <input type="datetime-local" value={dueAt} onChange={(e) => { setDueAt(e.target.value); setDueErr(''); }}
              className={clsx(cx.input, dueErr && cx.inputError)} />
            {dueErr && <p className="mt-1 text-[11px] text-[#ff6b6b]">{dueErr}</p>}
          </div>
          <div className="flex items-center gap-2">
            <input type="checkbox" id="calSync" checked={calendarSync} onChange={(e) => { setCalendarSync(e.target.checked); setDueErr(''); }}
              className="w-3.5 h-3.5 rounded accent-[#3b5bff]" />
            <label htmlFor="calSync" className={cx.text.body}>Google Calendar 동기화</label>
          </div>
          {isError && <div className={cx.errorBox}>생성에 실패했습니다.</div>}
          <div className="flex justify-end gap-2 pt-1">
            <button type="button" onClick={onClose} disabled={isPending} className={cx.btn.secondary}>취소</button>
            <button type="submit" disabled={isPending} className={cx.btn.primary}>{isPending ? '생성 중...' : '생성'}</button>
          </div>
        </form>
      </motion.div>
    </motion.div>
  );
}

// ── 메인 ──────────────────────────────────────────────────

export default function TaskListPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const pid = Number(projectId);

  const [statusFilter, setStatusFilter] = useState('');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [changingId, setChangingId] = useState<number | null>(null);
  const [summary, setSummary] = useState<ProjectWeeklySummary | null>(null);

  const { data: project } = useQuery({ queryKey: ['project', pid], queryFn: () => projectsApi.getProject(pid), enabled: !!pid });
  const { data: tasks, isLoading, isError } = useQuery({
    queryKey: ['tasks', pid, statusFilter],
    queryFn: () => tasksApi.getTasks(pid, statusFilter ? { status: statusFilter } : undefined),
    enabled: !!pid,
  });

  useEffect(() => {
    setSummary(null);
  }, [pid]);

  const summaryMutation = useMutation({
    mutationFn: () => projectsApi.generateWeeklySummary(pid),
    onSuccess: (data) => {
      setSummary(data);
    },
  });

  const createMutation = useMutation({
    mutationFn: (d: TaskCreateRequest) => tasksApi.createTask(pid, d),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks', pid] });
      setSummary(null);
      summaryMutation.reset();
      setIsModalOpen(false);
    },
  });

  const changeStatusMutation = useMutation({
    mutationFn: ({ id, to }: { id: number; to: TaskStatus }) => tasksApi.changeStatus(id, to),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks', pid] });
      setSummary(null);
      summaryMutation.reset();
      setChangingId(null);
    },
    onError: () => setChangingId(null),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => tasksApi.deleteTask(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks', pid] });
      setSummary(null);
      summaryMutation.reset();
      setDeletingId(null);
    },
    onError: () => setDeletingId(null),
  });

  if (isLoading) return <div className="flex items-center justify-center h-40"><span className={cx.text.meta}>로딩 중...</span></div>;
  if (isError) return <div className={cx.errorBox}>Task 목록을 불러오지 못했습니다.</div>;

  return (
    <div>
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-5">
        <div className="flex items-center gap-2.5">
          <button onClick={() => navigate('/projects')} className={clsx(cx.btn.ghost, 'flex items-center gap-1')}>
            ← 프로젝트
          </button>
          <span className="text-[#1e1e2a]">/</span>
          <h2 className={cx.text.heading}>{project?.name ?? '...'}</h2>
          {tasks && (
            <span className="text-[11px] text-[#a0a0bc] bg-[#111118] border border-[#252535] px-1.5 py-0.5 rounded-[3px]">
              {tasks.length}
            </span>
          )}
        </div>
        <button onClick={() => setIsModalOpen(true)} className={cx.btn.primary}>
          <span className="flex items-center gap-1.5"><Plus size={12} strokeWidth={2.5} />새 Task</span>
        </button>
      </div>

      <div className={clsx(cx.card, 'mb-5')}>
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <div className="flex items-center gap-2">
              <Sparkles size={14} strokeWidth={2} className="text-[#6ea8fe]" />
              <p className={cx.text.cardTitle}>이번 주 요약</p>
            </div>
            <p className={clsx(cx.text.meta, 'mt-2')}>
              현재 프로젝트 Task를 기반으로 이번 주 핵심 업무를 정리합니다. 버튼을 누를 때마다 새로 생성됩니다.
            </p>
          </div>

          <button
            onClick={() => {
              summaryMutation.reset();
              summaryMutation.mutate();
            }}
            disabled={summaryMutation.isPending}
            className={cx.btn.primary}
          >
            {summaryMutation.isPending ? '생성 중...' : summary ? '다시 생성' : '요약 생성'}
          </button>
        </div>

        {summaryMutation.isError && (
          <div className={clsx(cx.errorBox, 'mt-4')}>
            요약 생성에 실패했습니다. `GEMINI_API_KEY` 설정과 서버 로그를 확인해주세요.
          </div>
        )}

        {summary ? (
          <div className="mt-4 space-y-4">
            <div className="rounded-[10px] border border-[#252535] bg-[#101018] p-4">
              <p className="text-[13px] leading-6 text-[#e8e8ed]">{summary.summary}</p>
              <div className="mt-3 flex flex-wrap gap-2 text-[11px] text-[#8a8aa6]">
                <span>{summary.weekStart} ~ {summary.weekEnd}</span>
                <span>입력 Task {summary.includedTaskCount} / 전체 {summary.totalTaskCount}</span>
                <span>{new Date(summary.generatedAt).toLocaleString('ko-KR')}</span>
              </div>
            </div>

            {summary.highlights.length > 0 && (
              <div>
                <p className="mb-2 text-[11px] font-medium uppercase tracking-[0.16em] text-[#8ea7ff]">Highlights</p>
                <ul className="space-y-2">
                  {summary.highlights.map((item) => (
                    <li key={item} className="text-[13px] text-[#d8d8e5]">• {item}</li>
                  ))}
                </ul>
              </div>
            )}

            {summary.risks.length > 0 && (
              <div>
                <p className="mb-2 text-[11px] font-medium uppercase tracking-[0.16em] text-[#ff9f7a]">Risks</p>
                <ul className="space-y-2">
                  {summary.risks.map((item) => (
                    <li key={item} className="text-[13px] text-[#d8d8e5]">• {item}</li>
                  ))}
                </ul>
              </div>
            )}

            {summary.nextActions.length > 0 && (
              <div>
                <p className="mb-2 text-[11px] font-medium uppercase tracking-[0.16em] text-[#7dd3a7]">Next Actions</p>
                <ul className="space-y-2">
                  {summary.nextActions.map((item) => (
                    <li key={item} className="text-[13px] text-[#d8d8e5]">• {item}</li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        ) : (
          <p className={clsx(cx.text.meta, 'mt-4')}>아직 생성된 요약이 없습니다.</p>
        )}
      </div>

      {/* 필터 */}
      <div className="flex gap-1 mb-5">
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

      {/* Task 목록 */}
      {tasks && tasks.length === 0 ? (
        <div className={cx.emptyState}>
          <p className="text-[13px] mb-1">Task가 없습니다</p>
          <p className="text-[11px]">새 Task를 추가해보세요</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
          <AnimatePresence mode="popLayout">
            {tasks?.map((task) => (
              <motion.div
                key={task.id}
                layout
                initial={{ opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.97 }}
                transition={{ duration: 0.12 }}
              >
                <TaskCard
                  task={task}
                  onStatusChange={(id, to) => { setChangingId(id); changeStatusMutation.mutate({ id, to }); }}
                  onDelete={(id) => { if (!window.confirm('삭제하시겠습니까?')) return; setDeletingId(id); deleteMutation.mutate(id); }}
                  onClickDetail={(id) => navigate(`/tasks/${id}`)}
                  isChanging={changingId === task.id}
                  isDeleting={deletingId === task.id}
                />
              </motion.div>
            ))}
          </AnimatePresence>
        </div>
      )}

      <AnimatePresence>
        {isModalOpen && (
          <CreateModal
            onClose={() => setIsModalOpen(false)}
            onSubmit={(d) => createMutation.mutate(d)}
            isPending={createMutation.isPending}
            isError={createMutation.isError}
          />
        )}
      </AnimatePresence>
    </div>
  );
}
