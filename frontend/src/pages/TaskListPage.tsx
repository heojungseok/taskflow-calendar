import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { motion, AnimatePresence } from 'framer-motion';
import { Plus, Calendar, Clock, Sparkles, RefreshCw } from 'lucide-react';
import axios, { AxiosError } from 'axios';
import { tasksApi } from '@/api/endpoints/tasks';
import { projectsApi } from '@/api/endpoints/projects';
import type { ApiResponse } from '@/api/types';
import type { Task, TaskStatus, TaskCreateRequest, TaskUpdateRequest, TaskHistory, OutboxStatus } from '@/types/task';
import type { ProjectTaskRecommendation, ProjectTaskRecommendationItem, ProjectWeeklySummary, ProjectWeeklySummarySection } from '@/types/project';
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

const CHANGE_TYPE_LABEL: Record<string, string> = {
  STATUS: '상태 변경',
  ASSIGNEE: '담당자 변경',
  SCHEDULE: '일정 변경',
  CONTENT: '내용 변경',
};

const OUTBOX_STATUS_LABEL: Record<OutboxStatus, string> = {
  PENDING: '대기 중',
  PROCESSING: '처리 중',
  SUCCESS: '성공',
  FAILED: '실패',
};

const OUTBOX_BADGE: Record<OutboxStatus, string> = {
  PENDING: 'bg-[#1a1a20] text-[#9090a8] border border-[#252530]',
  PROCESSING: 'bg-[#1a2040] text-[#6b8cff] border border-[#2a3558]',
  SUCCESS: 'bg-[#0f2820] text-[#3dd68c] border border-[#1a4030]',
  FAILED: 'bg-[#2a1018] text-[#ff6b6b] border border-[#3d1520]',
};

const fmt = (iso?: string | null) => iso
  ? new Date(iso).toLocaleString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  : '—';

const toLocal = (iso?: string | null) => iso?.slice(0, 16) ?? '';

type SummaryApiError = AxiosError<ApiResponse<never>>;

function getSummaryError(error: unknown) {
  if (!axios.isAxiosError<ApiResponse<never>>(error)) {
    return null;
  }

  return error.response?.data.error ?? null;
}

function getSummaryErrorPresentation(error: unknown) {
  const apiError = getSummaryError(error);
  if (!apiError) {
    return {
      title: '요약 생성에 실패했습니다.',
      message: '잠시 후 다시 시도하고, 문제가 반복되면 서버 로그를 확인해주세요.',
      tone: 'error',
    } as const;
  }

  switch (apiError.code) {
    case 'LLM_RATE_LIMITED_TEMPORARY':
      return {
        title: '요약 호출이 잠시 몰려 있습니다.',
        message: '잠시 후 다시 시도해주세요. 저장된 요약이 있다면 그 결과를 먼저 보여드릴게요.',
        tone: 'warning',
      } as const;
    case 'LLM_QUOTA_EXHAUSTED':
      return {
        title: '현재 사용 가능한 요약 호출 한도를 소진했습니다.',
        message: '즉시 다시 생성하기보다 저장된 요약이 있다면 그 결과를 먼저 확인해주세요.',
        tone: 'warning',
      } as const;
    case 'LLM_429_UNKNOWN':
      return {
        title: '현재 요약 호출이 제한된 상태입니다.',
        message: '잠시 후 다시 시도해주세요. 제한 원인은 서버 로그에서 추가로 확인할 수 있습니다.',
        tone: 'warning',
      } as const;
    case 'LLM_API_KEY_MISSING':
    case 'LLM_CONFIG_INVALID':
      return {
        title: '서버 설정 문제로 요약을 생성할 수 없습니다.',
        message: '재시도보다 관리자 설정 확인이 먼저 필요합니다.',
        tone: 'error',
      } as const;
    case 'LLM_UPSTREAM_TEMPORARY_FAILURE':
      return {
        title: '일시적으로 요약 생성에 실패했습니다.',
        message: '잠시 후 다시 시도해주세요.',
        tone: 'warning',
      } as const;
    case 'LLM_INVALID_RESPONSE':
      return {
        title: '요약 응답 형식이 올바르지 않습니다.',
        message: '서버 로그를 확인해주세요.',
        tone: 'error',
      } as const;
    default:
      return {
        title: '요약 생성에 실패했습니다.',
        message: apiError.message,
        tone: 'error',
      } as const;
  }
}

function getCacheBadge(summary: ProjectWeeklySummary | null) {
  if (!summary) {
    return null;
  }

  switch (summary.cacheStatus) {
    case 'CACHE_HIT':
      return {
        label: '캐시 응답',
        className: 'bg-[#1a2040] text-[#8ea7ff] border border-[#2a3558]',
        message: '같은 입력으로 생성된 저장본을 반환했습니다.',
      };
    case 'STALE_FALLBACK':
      return {
        label: '저장된 요약',
        className: 'bg-[#2b2412] text-[#ffcf7a] border border-[#4a3b14]',
        message: '최신 호출 대신 마지막 성공 요약을 보여주고 있습니다.',
      };
    default:
      return {
        label: '실시간 생성',
        className: 'bg-[#0f2820] text-[#7dd3a7] border border-[#1a4030]',
        message: '현재 Task 상태 기준으로 새로 생성했습니다.',
      };
  }
}

function getRecommendationMeta(recommendation: ProjectTaskRecommendation | null) {
  if (!recommendation) {
    return null;
  }

  if (recommendation.cacheStatus === 'CACHE_HIT') {
    return {
      label: '캐시 응답',
      className: 'bg-[#1a2040] text-[#8ea7ff] border border-[#2a3558]',
    } as const;
  }

  return {
    label: '실시간 생성',
    className: 'bg-[#0f2820] text-[#7dd3a7] border border-[#1a4030]',
  } as const;
}

function getRecommendationErrorPresentation(error: unknown) {
  const apiError = getSummaryError(error);
  if (!apiError) {
    return {
      title: '추천을 불러오지 못했습니다.',
      message: '잠시 후 다시 시도해주세요.',
      tone: 'error',
    } as const;
  }

  switch (apiError.code) {
    case 'LLM_RATE_LIMITED_TEMPORARY':
      return {
        title: '추천 호출이 잠시 몰려 있습니다.',
        message: '잠시 후 다시 시도해주세요.',
        tone: 'warning',
      } as const;
    case 'LLM_QUOTA_EXHAUSTED':
      return {
        title: '현재 추천 호출 한도를 소진했습니다.',
        message: '잠시 후 다시 시도해주세요.',
        tone: 'warning',
      } as const;
    case 'LLM_429_UNKNOWN':
      return {
        title: '현재 추천 호출이 제한된 상태입니다.',
        message: '잠시 후 다시 시도해주세요.',
        tone: 'warning',
      } as const;
    case 'LLM_API_KEY_MISSING':
    case 'LLM_CONFIG_INVALID':
      return {
        title: '서버 설정 문제로 추천을 생성할 수 없습니다.',
        message: '환경 설정을 먼저 확인해야 합니다.',
        tone: 'error',
      } as const;
    case 'LLM_UPSTREAM_TEMPORARY_FAILURE':
      return {
        title: '일시적으로 추천 생성에 실패했습니다.',
        message: '잠시 후 다시 시도해주세요.',
        tone: 'warning',
      } as const;
    case 'LLM_INVALID_RESPONSE':
      return {
        title: '추천 응답 형식이 올바르지 않습니다.',
        message: '서버 로그를 확인해주세요.',
        tone: 'error',
      } as const;
    default:
      return {
        title: '추천을 불러오지 못했습니다.',
        message: apiError.message,
        tone: 'error',
      } as const;
  }
}

// ── Task 카드 ─────────────────────────────────────────────

interface TaskCardProps {
  task: Task;
  onStatusChange: (id: number, to: TaskStatus) => void;
  onDelete: (id: number) => void;
  onClickDetail: (id: number) => void;
  isChanging: boolean;
  isDeleting: boolean;
  variant?: 'list' | 'recommended';
  recommendation?: ProjectTaskRecommendationItem;
  showRecommendationMarker?: boolean;
}

function TaskCard({
  task,
  onStatusChange,
  onDelete,
  onClickDetail,
  isChanging,
  isDeleting,
  variant = 'list',
  recommendation,
  showRecommendationMarker = false,
}: TaskCardProps) {
  const next = ALLOWED_TRANSITIONS[task.status];
  const isRecommended = variant === 'recommended';
  const clickableClass = isRecommended ? cx.cardInteractive : cx.card;
  const hasTopPillRow = Boolean(recommendation) || (showRecommendationMarker && !isRecommended);

  return (
    <div
      className={clsx(
        clickableClass,
        'flex h-full flex-col',
        isRecommended
          ? 'min-w-[272px] max-w-[304px] gap-3 snap-start rounded-[10px] p-3.5'
          : 'min-h-[184px]'
      )}
      onClick={isRecommended ? () => onClickDetail(task.id) : undefined}
    >
      {isRecommended ? (
        <div className="group flex min-h-0 flex-1 flex-col">
          <div className={clsx('flex items-start justify-between gap-3', hasTopPillRow && 'mb-2')}>
            <div className="min-w-0 flex-1">
              {hasTopPillRow && (
                <div className="mb-2 flex items-center gap-1.5 flex-wrap">
                  {recommendation && (
                    <span className="inline-flex items-center rounded-full border border-[#2a3558] bg-[#131f36] px-2 py-0.5 text-[10px] font-semibold tracking-wide text-[#8ea7ff]">
                      추천 {recommendation.rank}
                    </span>
                  )}
                </div>
              )}

              <p
                className={clsx(
                  cx.text.cardTitle,
                  'text-[14px] text-[#eef0f8] leading-snug transition-colors duration-150 group-hover:text-[#eef2ff]'
                )}
              >
                {task.title}
              </p>
            </div>
          </div>

          <div className="mb-2 flex items-center gap-1.5 flex-wrap">
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

          <p className={clsx(cx.text.meta, 'mt-2 flex min-h-[16px] items-center gap-1')}>
            {task.dueAt ? (
              <>
                <Clock size={10} strokeWidth={2} />
                {new Date(task.dueAt).toLocaleDateString('ko-KR')}
              </>
            ) : (
              '마감일 없음'
            )}
          </p>

          {recommendation?.reason && (
            <p className={clsx(cx.text.body, 'mt-2 leading-relaxed [display:-webkit-box] [-webkit-line-clamp:2] [-webkit-box-orient:vertical] overflow-hidden')}>
              {recommendation.reason}
            </p>
          )}

          {recommendation && (
            <div className="mt-3 flex items-center gap-1.5 flex-wrap">
              <span className="inline-flex items-center rounded-full border border-[#2f2344] bg-[#191224] px-2 py-0.5 text-[10px] font-medium text-[#f1b6ff]">
                {recommendation.primaryTag}
              </span>
              {recommendation.secondaryTag && (
                <span className="inline-flex items-center rounded-full border border-[#234440] bg-[#12211f] px-2 py-0.5 text-[10px] font-medium text-[#8fe2ca]">
                  {recommendation.secondaryTag}
                </span>
              )}
            </div>
          )}
        </div>
      ) : (
        <button
          type="button"
          onClick={() => onClickDetail(task.id)}
          className="group flex min-h-[106px] flex-1 flex-col text-left"
        >
          {hasTopPillRow && (
            <div className="mb-2 flex min-h-[18px] items-center gap-1.5 flex-wrap">
              {showRecommendationMarker && (
                <span className="inline-flex items-center rounded-full border border-[#2a3558] bg-[#131f36] px-2 py-0.5 text-[10px] font-semibold tracking-wide text-[#8ea7ff]">
                  추천
                </span>
              )}
            </div>
          )}

          <p className={clsx(cx.text.cardTitle, 'min-h-[38px] leading-snug transition-colors duration-150 group-hover:text-[#e8e8ed] [display:-webkit-box] [-webkit-line-clamp:2] [-webkit-box-orient:vertical] overflow-hidden')}>
            {task.title}
          </p>

          <div className="mt-2.5 flex min-h-[24px] items-center gap-1.5 flex-wrap">
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

          <p className={clsx(cx.text.meta, 'mt-2 flex min-h-[16px] items-center gap-1')}>
            {task.dueAt ? (
              <>
                <Clock size={10} strokeWidth={2} />
                {new Date(task.dueAt).toLocaleDateString('ko-KR')}
              </>
            ) : (
              '마감일 없음'
            )}
          </p>
        </button>
      )}

      {!isRecommended && (
        <div className={clsx(cx.divider, 'mt-auto pt-2.5 flex items-center justify-between gap-3')}>
          <div className="flex gap-1">
            {next.map((s) => (
              <button
                key={s}
                type="button"
                onClick={(event) => {
                  event.stopPropagation();
                  onStatusChange(task.id, s);
                }}
                disabled={isChanging}
                className={cx.btn.statusTransition}
              >
                {STATUS_LABEL[s]}
              </button>
            ))}
            {next.length === 0 && <span className={cx.text.meta}>완료됨</span>}
          </div>
          <button
            type="button"
            onClick={(event) => {
              event.stopPropagation();
              onDelete(task.id);
            }}
            disabled={isDeleting}
            className={cx.btn.danger}
          >
            삭제
          </button>
        </div>
      )}
    </div>
  );
}

function RecommendationSection({
  recommendation,
  status,
  hasGeneratedOnce,
  error,
  isOutdated,
  onClickDetail,
  onGenerate,
  onRefresh,
  tasks,
}: {
  recommendation: ProjectTaskRecommendation | null;
  status: 'idle' | 'loading' | 'success' | 'error';
  hasGeneratedOnce: boolean;
  error: unknown;
  isOutdated: boolean;
  onClickDetail: (id: number) => void;
  onGenerate: () => void;
  onRefresh: () => void;
  tasks: Task[] | undefined;
}) {
  const recommendationError = getRecommendationErrorPresentation(error);
  const recommendationMeta = getRecommendationMeta(recommendation);
  const hasRecommendation = status === 'success' && recommendation !== null;
  const showError = status === 'error';
  const isLoading = status === 'loading';
  const showRefreshButton = hasGeneratedOnce;

  return (
    <div className={clsx(cx.card, 'mb-5 overflow-hidden')}>
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <Sparkles size={14} strokeWidth={2} className="text-[#f1b6ff]" />
            <p className={cx.text.cardTitle}>지금 먼저 볼 Task</p>
          </div>
          <p className={clsx(cx.text.meta, 'mt-2')}>
            지금 우선 확인하면 좋은 업무를 추천합니다.
          </p>
        </div>
        {showRefreshButton ? (
          <button
            type="button"
            onClick={onRefresh}
            disabled={isLoading}
            className={clsx(cx.btn.secondary, 'inline-flex items-center justify-center px-2 py-2')}
            title="추천 새로고침"
            aria-label="추천 새로고침"
          >
            <RefreshCw size={13} className={clsx(isLoading && 'animate-spin')} />
          </button>
        ) : (
          <button
            type="button"
            onClick={onGenerate}
            disabled={isLoading}
            className={cx.btn.primary}
          >
            {isLoading ? '생성 중...' : '추천 생성'}
          </button>
        )}
      </div>

      {hasRecommendation && recommendationMeta && (
        <div className="mt-4 flex flex-wrap items-center gap-2 text-[11px] text-[#8a8aa6]">
          <span>마지막 생성 {new Date(recommendation.generatedAt).toLocaleString('ko-KR')}</span>
          <span className={clsx('inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium', recommendationMeta.className)}>
            {recommendationMeta.label}
          </span>
        </div>
      )}

      {hasRecommendation && isOutdated && (
        <div className="mt-4 rounded-[10px] border border-[#3a3220] bg-[#18140d] px-3 py-2 text-[12px] text-[#e6c27f]">
          Task 변경 이후 다시 추천받아 최신 우선순위를 확인하세요.
        </div>
      )}

      {isLoading && !hasRecommendation && (
        <p className={clsx(cx.text.meta, 'mt-4')}>추천을 불러오는 중입니다...</p>
      )}

      {showError && (
        <div
          className={clsx(
            'mt-4 rounded-[10px] border px-3 py-3 text-[12px]',
            recommendationError?.tone === 'warning'
              ? 'border-[#4a3b14] bg-[#20190c] text-[#ffcf7a]'
              : cx.errorBox
          )}
        >
          <p className="font-medium">{recommendationError?.title}</p>
          <p className="mt-1 opacity-90">{recommendationError?.message}</p>
        </div>
      )}

      {hasRecommendation && recommendation.items.length === 0 && (
        <p className={clsx(cx.text.meta, 'mt-4')}>현재 추천할 미완료 Task가 없습니다.</p>
      )}

      {hasRecommendation && recommendation.items.length > 0 && (
        <div className="mt-4 -mx-4 px-4 overflow-x-auto">
          <div className="flex gap-3 pb-2 snap-x snap-mandatory">
            {recommendation.items.map((item) => {
              const task = tasks?.find((candidate) => candidate.id === item.taskId) ?? {
                id: item.taskId,
                projectId: recommendation.projectId,
                title: item.title,
                description: null,
                status: item.status,
                assigneeUserId: null,
                assigneeName: null,
                startAt: null,
                dueAt: item.dueAt,
                calendarSyncEnabled: item.calendarSyncEnabled,
                calendarEventId: item.calendarEventId,
                createdAt: recommendation.generatedAt,
                updatedAt: recommendation.generatedAt,
              };

              return (
                <TaskCard
                  key={item.taskId}
                  task={task}
                  variant="recommended"
                  recommendation={item}
                  onStatusChange={() => {}}
                  onDelete={() => {}}
                  onClickDetail={onClickDetail}
                  isChanging={false}
                  isDeleting={false}
                />
              );
            })}
          </div>
        </div>
      )}
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
  const [startAt, setStartAt] = useState('');
  const [dueAt, setDueAt] = useState('');
  const [calendarSync, setCalendarSync] = useState(false);
  const [titleErr, setTitleErr] = useState('');
  const [startErr, setStartErr] = useState('');
  const [dueErr, setDueErr] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    let ok = true;
    if (!title.trim()) { setTitleErr('제목을 입력해주세요.'); ok = false; }
    if (calendarSync && !startAt) { setStartErr('캘린더 동기화에는 시작일이 필요합니다.'); ok = false; }
    if (calendarSync && !dueAt) { setDueErr('캘린더 동기화에는 마감일이 필요합니다.'); ok = false; }
    if (startAt && dueAt && startAt > dueAt) { setDueErr('시작일은 마감일보다 늦을 수 없습니다.'); ok = false; }
    if (!ok) return;
    onSubmit({
      title: title.trim(),
      description: description.trim() || undefined,
      startAt: startAt ? `${startAt}:00` : undefined,
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
            <label className={cx.text.label}>시작일</label>
            <input type="datetime-local" value={startAt} onChange={(e) => { setStartAt(e.target.value); setStartErr(''); }}
              className={clsx(cx.input, startErr && cx.inputError)} />
            {startErr && <p className="mt-1 text-[11px] text-[#ff6b6b]">{startErr}</p>}
          </div>
          <div>
            <label className={cx.text.label}>마감일</label>
            <input type="datetime-local" value={dueAt} onChange={(e) => { setDueAt(e.target.value); setDueErr(''); }}
              className={clsx(cx.input, dueErr && cx.inputError)} />
            {dueErr && <p className="mt-1 text-[11px] text-[#ff6b6b]">{dueErr}</p>}
          </div>
          <div className="flex items-center gap-2">
            <input type="checkbox" id="calSync" checked={calendarSync} onChange={(e) => { setCalendarSync(e.target.checked); setStartErr(''); setDueErr(''); }}
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

interface SummarySectionProps {
  title: string;
  section: ProjectWeeklySummarySection;
  accentClass: string;
}

function SummarySectionCard({ title, section, accentClass }: SummarySectionProps) {
  return (
    <div className="rounded-[12px] border border-[#252535] bg-[#101018] p-4">
      <div>
        <p className={clsx('text-[11px] font-medium uppercase tracking-[0.16em]', accentClass)}>{title}</p>
      </div>

      <p className="mt-4 text-[13px] leading-6 text-[#e8e8ed]">{section.summary}</p>

      {section.highlights.length > 0 && (
        <div className="mt-4">
          <p className="mb-2 text-[11px] font-medium uppercase tracking-[0.16em] text-[#8ea7ff]">핵심 포인트</p>
          <ul className="space-y-2">
            {section.highlights.map((item) => (
              <li key={item} className="text-[13px] text-[#d8d8e5]">• {item}</li>
            ))}
          </ul>
        </div>
      )}

      {section.risks.length > 0 && (
        <div className="mt-4">
          <p className="mb-2 text-[11px] font-medium uppercase tracking-[0.16em] text-[#ff9f7a]">주의</p>
          <ul className="space-y-2">
            {section.risks.map((item) => (
              <li key={item} className="text-[13px] text-[#d8d8e5]">• {item}</li>
            ))}
          </ul>
        </div>
      )}

      {section.nextActions.length > 0 && (
        <div className="mt-4">
          <p className="mb-2 text-[11px] font-medium uppercase tracking-[0.16em] text-[#7dd3a7]">고려 사항</p>
          <ul className="space-y-2">
            {section.nextActions.map((item) => (
              <li key={item} className="text-[13px] text-[#d8d8e5]">• {item}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

interface TaskDetailModalProps {
  taskId: number;
  onClose: () => void;
  onTaskUpdated: () => void;
  onStatusChange: (id: number, to: TaskStatus) => void;
  onDelete: (id: number) => void;
  changingId: number | null;
  deletingId: number | null;
}

function TaskDetailModal({ taskId, onClose, onTaskUpdated, onStatusChange, onDelete, changingId, deletingId }: TaskDetailModalProps) {
  const queryClient = useQueryClient();
  const { data: task, isLoading, isError } = useQuery({
    queryKey: ['task', taskId],
    queryFn: () => tasksApi.getTask(taskId),
    enabled: !!taskId,
  });
  const { data: syncStatus } = useQuery({
    queryKey: ['task-sync', taskId],
    queryFn: () => tasksApi.getSyncStatus(taskId),
    enabled: !!taskId,
  });
  const { data: history } = useQuery({
    queryKey: ['task-history', taskId],
    queryFn: () => tasksApi.getHistory(taskId),
    enabled: !!taskId,
  });
  const [isEditing, setIsEditing] = useState(false);
  const [editTitle, setEditTitle] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [editStartAt, setEditStartAt] = useState('');
  const [editDueAt, setEditDueAt] = useState('');
  const [editTitleError, setEditTitleError] = useState('');
  const [editStartError, setEditStartError] = useState('');
  const [editDueError, setEditDueError] = useState('');

  useEffect(() => {
    if (!task) return;
    setEditTitle(task.title);
    setEditDescription(task.description ?? '');
    setEditStartAt(toLocal(task.startAt));
    setEditDueAt(toLocal(task.dueAt));
    setEditTitleError('');
    setEditStartError('');
    setEditDueError('');
    setIsEditing(false);
  }, [task?.id, task?.title, task?.description, task?.startAt, task?.dueAt]);

  const updateMutation = useMutation({
    mutationFn: (payload: TaskUpdateRequest) => tasksApi.updateTask(taskId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      queryClient.invalidateQueries({ queryKey: ['task', taskId] });
      queryClient.invalidateQueries({ queryKey: ['task-history', taskId] });
      queryClient.invalidateQueries({ queryKey: ['task-sync', taskId] });
      onTaskUpdated();
      setIsEditing(false);
    },
  });

  const next = task ? ALLOWED_TRANSITIONS[task.status] : [];

  return (
    <motion.div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-[1px] px-3 py-6 sm:px-6"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.14 }}
      onClick={onClose}
    >
      <motion.div
        className="mx-auto max-h-full w-full max-w-2xl overflow-y-auto rounded-[14px] border border-[#2a2a3a] bg-[#0f0f17] p-5"
        initial={{ opacity: 0, y: 8, scale: 0.98 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        exit={{ opacity: 0, y: 8, scale: 0.98 }}
        transition={{ duration: 0.14 }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <p className={cx.text.subheading}>Task 상세</p>
          <button type="button" onClick={onClose} className={cx.btn.secondary}>닫기</button>
        </div>

        {isLoading && <div className={cx.text.meta}>로딩 중...</div>}
        {isError && <div className={cx.errorBox}>Task 상세를 불러오지 못했습니다.</div>}

        {task && (
          <div className="space-y-4">
            <div className={cx.card}>
              <div className="mb-2 flex items-center justify-between gap-2">
                <h3 className="text-[15px] font-semibold text-[#ececf2]">{task.title}</h3>
                {!isEditing ? (
                  <button type="button" onClick={() => setIsEditing(true)} className={cx.btn.secondary}>수정</button>
                ) : (
                  <button
                    type="button"
                    onClick={() => {
                      setIsEditing(false);
                      setEditTitle(task.title);
                      setEditDescription(task.description ?? '');
                      setEditStartAt(toLocal(task.startAt));
                      setEditDueAt(toLocal(task.dueAt));
                      setEditTitleError('');
                      setEditStartError('');
                      setEditDueError('');
                    }}
                    className={cx.btn.secondary}
                  >
                    취소
                  </button>
                )}
              </div>
              <div className="mb-3 flex flex-wrap items-center gap-1.5">
                <span className={clsx(cx.badge.base, cx.badge[task.status])}>{STATUS_LABEL[task.status]}</span>
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

              {isEditing ? (
                <form
                  className="mb-4 space-y-3"
                  onSubmit={(e) => {
                    e.preventDefault();
                    let valid = true;
                    if (!editTitle.trim()) {
                      setEditTitleError('제목을 입력해주세요.');
                      valid = false;
                    }
                    if (task.calendarSyncEnabled && !editStartAt) {
                      setEditStartError('캘린더 동기화에는 시작일이 필요합니다.');
                      valid = false;
                    }
                    if (task.calendarSyncEnabled && !editDueAt) {
                      setEditDueError('캘린더 동기화에는 마감일이 필요합니다.');
                      valid = false;
                    }
                    if (editStartAt && editDueAt && editStartAt > editDueAt) {
                      setEditDueError('시작일은 마감일보다 늦을 수 없습니다.');
                      valid = false;
                    }
                    if (!valid) {
                      return;
                    }
                    updateMutation.mutate({
                      title: editTitle.trim(),
                      description: editDescription.trim() || undefined,
                      startAt: editStartAt ? `${editStartAt}:00` : undefined,
                      dueAt: editDueAt ? `${editDueAt}:00` : undefined,
                    });
                  }}
                >
                  <div>
                    <label className={cx.text.label}>제목</label>
                    <input
                      type="text"
                      value={editTitle}
                      onChange={(e) => { setEditTitle(e.target.value); setEditTitleError(''); }}
                      className={clsx(cx.input, editTitleError && cx.inputError)}
                    />
                    {editTitleError && <p className="mt-1 text-[11px] text-[#ff6b6b]">{editTitleError}</p>}
                  </div>
                  <div>
                    <label className={cx.text.label}>설명</label>
                    <textarea
                      value={editDescription}
                      onChange={(e) => setEditDescription(e.target.value)}
                      rows={4}
                      className={cx.textarea}
                    />
                  </div>
                  <div>
                    <label className={cx.text.label}>시작일</label>
                    <input
                      type="datetime-local"
                      value={editStartAt}
                      onChange={(e) => { setEditStartAt(e.target.value); setEditStartError(''); setEditDueError(''); }}
                      className={clsx(cx.input, editStartError && cx.inputError)}
                    />
                    {editStartError && <p className="mt-1 text-[11px] text-[#ff6b6b]">{editStartError}</p>}
                  </div>
                  <div>
                    <label className={cx.text.label}>마감일</label>
                    <input
                      type="datetime-local"
                      value={editDueAt}
                      onChange={(e) => { setEditDueAt(e.target.value); setEditDueError(''); }}
                      className={clsx(cx.input, editDueError && cx.inputError)}
                    />
                    {editDueError && <p className="mt-1 text-[11px] text-[#ff6b6b]">{editDueError}</p>}
                  </div>
                  {updateMutation.isError && <div className={cx.errorBox}>수정에 실패했습니다.</div>}
                  <div className="flex justify-end">
                    <button type="submit" disabled={updateMutation.isPending} className={cx.btn.primary}>
                      {updateMutation.isPending ? '저장 중...' : '저장'}
                    </button>
                  </div>
                </form>
              ) : task.description ? (
                <p className="mb-4 whitespace-pre-wrap text-[13px] leading-6 text-[#d8d8e6]">{task.description}</p>
              ) : (
                <p className={clsx(cx.text.meta, 'mb-4')}>설명이 없습니다.</p>
              )}

              <div className="grid gap-2 text-[12px] text-[#b8b8c8] sm:grid-cols-2">
                <p>시작일: {fmt(task.startAt)}</p>
                <p>마감일: {fmt(task.dueAt)}</p>
                <p>생성일: {fmt(task.createdAt)}</p>
                <p>수정일: {fmt(task.updatedAt)}</p>
              </div>

              <div className={clsx(cx.divider, 'mt-4 pt-3 flex flex-wrap items-center gap-1.5')}>
                {next.map((s) => (
                  <button
                    key={s}
                    onClick={() => onStatusChange(task.id, s)}
                    disabled={changingId === task.id}
                    className={cx.btn.statusTransition}
                  >
                    → {STATUS_LABEL[s]}
                  </button>
                ))}
                <button
                  onClick={() => onDelete(task.id)}
                  disabled={deletingId === task.id}
                  className={cx.btn.danger}
                >
                  삭제
                </button>
              </div>
            </div>

            <div className={cx.card}>
              <p className={clsx(cx.text.subheading, 'mb-3')}>캘린더 동기화</p>
              {!task.calendarSyncEnabled ? (
                <p className={cx.text.meta}>동기화 비활성화 상태입니다.</p>
              ) : (
                <div className="space-y-2 text-[12px] text-[#c8c8d8]">
                  <div className="flex items-center gap-2">
                    <span className={cx.text.meta}>상태</span>
                    {syncStatus?.lastOutboxStatus
                      ? <span className={clsx(cx.badge.base, OUTBOX_BADGE[syncStatus.lastOutboxStatus])}>{OUTBOX_STATUS_LABEL[syncStatus.lastOutboxStatus]}</span>
                      : <span className={cx.text.meta}>—</span>}
                  </div>
                  <p>마지막 성공: {fmt(syncStatus?.lastSyncedAt)}</p>
                  {syncStatus?.lastOutboxError && <p className="text-[#ff9f9f]">오류: {syncStatus.lastOutboxError}</p>}
                </div>
              )}
            </div>

            <div className={cx.card}>
              <p className={clsx(cx.text.subheading, 'mb-3')}>변경 이력</p>
              {!history || history.length === 0 ? (
                <p className={cx.text.meta}>변경 이력이 없습니다.</p>
              ) : (
                <div className="space-y-3">
                  {history.slice(0, 8).map((h: TaskHistory, i: number) => (
                    <div key={`${h.createdAt}-${i}`} className={clsx(i > 0 && clsx(cx.divider, 'pt-3'))}>
                      <div className="mb-1.5 flex items-center justify-between">
                        <span className="text-[12px] text-[#d8d8e6]">{CHANGE_TYPE_LABEL[h.changeType] ?? h.changeType}</span>
                        <span className={cx.text.meta}>{fmt(h.createdAt)}</span>
                      </div>
                      <p className={cx.text.meta}>{h.changedByUserName}</p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
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
  const [selectedTaskId, setSelectedTaskId] = useState<number | null>(null);
  const [summary, setSummary] = useState<ProjectWeeklySummary | null>(null);
  const [shouldAutoRefreshSummary, setShouldAutoRefreshSummary] = useState(false);
  const [recommendation, setRecommendation] = useState<ProjectTaskRecommendation | null>(null);
  const [recommendationStatus, setRecommendationStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle');
  const [recommendationError, setRecommendationError] = useState<unknown>(null);
  const [hasGeneratedRecommendation, setHasGeneratedRecommendation] = useState(false);
  const [isRecommendationOutdated, setIsRecommendationOutdated] = useState(false);

  const { data: project } = useQuery({ queryKey: ['project', pid], queryFn: () => projectsApi.getProject(pid), enabled: !!pid });
  const { data: tasks, isLoading, isError } = useQuery({
    queryKey: ['tasks', pid, statusFilter],
    queryFn: () => tasksApi.getTasks(pid, statusFilter ? { status: statusFilter } : undefined),
    enabled: !!pid,
  });

  useEffect(() => {
    setSummary(null);
    setRecommendation(null);
    setRecommendationStatus('idle');
    setRecommendationError(null);
    setHasGeneratedRecommendation(false);
    setIsRecommendationOutdated(false);
  }, [pid]);

  const summaryMutation = useMutation<ProjectWeeklySummary, SummaryApiError>({
    mutationFn: () => projectsApi.generateWeeklySummary(pid),
    onSuccess: (data) => {
      setSummary(data);
      setShouldAutoRefreshSummary(false);
    },
  });
  const summaryError = getSummaryErrorPresentation(summaryMutation.error);
  const cacheBadge = getCacheBadge(summary);

  useEffect(() => {
    if (!shouldAutoRefreshSummary) return;
    if (summaryMutation.isPending) return;
    summaryMutation.mutate();
  }, [shouldAutoRefreshSummary, summaryMutation]);

  const recommendationMutation = useMutation<ProjectTaskRecommendation, SummaryApiError>({
    mutationFn: () => projectsApi.getTaskRecommendations(pid),
    onMutate: () => {
      setRecommendationError(null);
      setRecommendationStatus('loading');
    },
    onSuccess: (data) => {
      setRecommendation(data);
      setRecommendationStatus('success');
      setRecommendationError(null);
      setHasGeneratedRecommendation(true);
      setIsRecommendationOutdated(false);
    },
    onError: (error) => {
      setRecommendation(null);
      setRecommendationStatus('error');
      setRecommendationError(error);
    },
  });

  const markRecommendationOutdated = () => {
    if (recommendationStatus === 'success') {
      setIsRecommendationOutdated(true);
    }
  };

  const createMutation = useMutation({
    mutationFn: (d: TaskCreateRequest) => tasksApi.createTask(pid, d),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks', pid] });
      markRecommendationOutdated();
      if (summary) {
        setShouldAutoRefreshSummary(true);
      } else {
        setSummary(null);
        summaryMutation.reset();
      }
      setIsModalOpen(false);
    },
  });

  const changeStatusMutation = useMutation({
    mutationFn: ({ id, to }: { id: number; to: TaskStatus }) => tasksApi.changeStatus(id, to),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['tasks', pid] });
      queryClient.invalidateQueries({ queryKey: ['task', variables.id] });
      queryClient.invalidateQueries({ queryKey: ['task-history', variables.id] });
      queryClient.invalidateQueries({ queryKey: ['task-sync', variables.id] });
      markRecommendationOutdated();
      if (summary) {
        setShouldAutoRefreshSummary(true);
      } else {
        setSummary(null);
        summaryMutation.reset();
      }
      setChangingId(null);
    },
    onError: () => setChangingId(null),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => tasksApi.deleteTask(id),
    onSuccess: (_data, deletedId) => {
      queryClient.invalidateQueries({ queryKey: ['tasks', pid] });
      markRecommendationOutdated();
      if (summary) {
        setShouldAutoRefreshSummary(true);
      } else {
        setSummary(null);
        summaryMutation.reset();
      }
      setDeletingId(null);
      if (selectedTaskId === deletedId) {
        setSelectedTaskId(null);
      }
    },
    onError: () => setDeletingId(null),
  });

  if (isLoading) return <div className="flex items-center justify-center h-40"><span className={cx.text.meta}>로딩 중...</span></div>;
  if (isError) return <div className={cx.errorBox}>Task 목록을 불러오지 못했습니다.</div>;

  const recommendedTaskIds = new Set((recommendation?.items ?? []).map((item) => item.taskId));

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
              현재 프로젝트 Task를 Google Calendar 반영 여부로 나눠 이번 주 흐름을 정리합니다. 버튼을 누를 때마다 새로 생성됩니다.
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
          <div
            className={clsx(
              'mt-4 rounded-[10px] border px-3 py-3 text-[12px]',
              summaryError?.tone === 'warning'
                ? 'border-[#4a3b14] bg-[#20190c] text-[#ffcf7a]'
                : cx.errorBox
            )}
          >
            <p className="font-medium">{summaryError?.title}</p>
            <p className="mt-1 opacity-90">{summaryError?.message}</p>
          </div>
        )}

        {summary ? (
          <div className="mt-4 space-y-4">
            <div className="flex flex-wrap gap-2 text-[11px] text-[#8a8aa6]">
              <span>{summary.weekStart} ~ {summary.weekEnd}</span>
              <span>전체 {summary.totalTaskCount}</span>
              <span>동기화 {summary.syncedTaskCount}</span>
              <span>미동기화 {summary.unsyncedTaskCount}</span>
              <span>{new Date(summary.generatedAt).toLocaleString('ko-KR')}</span>
              {cacheBadge && (
                <span className={clsx('inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium', cacheBadge.className)}>
                  {cacheBadge.label}
                </span>
              )}
            </div>

            {cacheBadge && (
              <div className="rounded-[10px] border border-[#252535] bg-[#0d0d14] px-3 py-2 text-[12px] text-[#b8b8c8]">
                {cacheBadge.message}
              </div>
            )}

            <div className="grid gap-4 lg:grid-cols-2">
              <SummarySectionCard
                title="동기화된 일정"
                section={summary.synced}
                accentClass="text-[#8ea7ff]"
              />
              <SummarySectionCard
                title="미동기화 일정"
                section={summary.unsynced}
                accentClass="text-[#ffb482]"
              />
            </div>
          </div>
        ) : (
          <p className={clsx(cx.text.meta, 'mt-4')}>아직 생성된 요약이 없습니다.</p>
        )}
      </div>

      <RecommendationSection
        recommendation={recommendation}
        status={recommendationStatus}
        hasGeneratedOnce={hasGeneratedRecommendation}
        error={recommendationError}
        isOutdated={isRecommendationOutdated}
        onClickDetail={(id) => setSelectedTaskId(id)}
        onGenerate={() => { recommendationMutation.mutate(); }}
        onRefresh={() => { recommendationMutation.mutate(); }}
        tasks={tasks}
      />

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
                className="h-full"
                initial={{ opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.97 }}
                transition={{ duration: 0.12 }}
              >
                <TaskCard
                  task={task}
                  onStatusChange={(id, to) => { setChangingId(id); changeStatusMutation.mutate({ id, to }); }}
                  onDelete={(id) => { if (!window.confirm('삭제하시겠습니까?')) return; setDeletingId(id); deleteMutation.mutate(id); }}
                  onClickDetail={(id) => setSelectedTaskId(id)}
                  isChanging={changingId === task.id}
                  isDeleting={deletingId === task.id}
                  showRecommendationMarker={recommendedTaskIds.has(task.id)}
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

      <AnimatePresence>
        {selectedTaskId && (
          <TaskDetailModal
            taskId={selectedTaskId}
            onClose={() => setSelectedTaskId(null)}
            onTaskUpdated={() => {
              queryClient.invalidateQueries({ queryKey: ['tasks', pid] });
              markRecommendationOutdated();
              if (summary) {
                setShouldAutoRefreshSummary(true);
              } else {
                setSummary(null);
                summaryMutation.reset();
              }
            }}
            onStatusChange={(id, to) => {
              setChangingId(id);
              changeStatusMutation.mutate({ id, to });
            }}
            onDelete={(id) => {
              if (!window.confirm('삭제하시겠습니까?')) return;
              setDeletingId(id);
              deleteMutation.mutate(id);
            }}
            changingId={changingId}
            deletingId={deletingId}
          />
        )}
      </AnimatePresence>
    </div>
  );
}
