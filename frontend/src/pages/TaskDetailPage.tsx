import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { tasksApi } from '@/api/endpoints/tasks';
import type { TaskStatus, TaskUpdateRequest, OutboxStatus } from '@/types/task';

// ===== 상수 =====

const STATUS_LABEL: Record<TaskStatus, string> = {
  REQUESTED: '요청됨',
  IN_PROGRESS: '진행 중',
  DONE: '완료',
  BLOCKED: '차단됨',
};

const STATUS_COLOR: Record<TaskStatus, string> = {
  REQUESTED: 'bg-gray-100 text-gray-700',
  IN_PROGRESS: 'bg-blue-100 text-blue-700',
  DONE: 'bg-green-100 text-green-700',
  BLOCKED: 'bg-red-100 text-red-700',
};

const ALLOWED_TRANSITIONS: Record<TaskStatus, TaskStatus[]> = {
  REQUESTED: ['IN_PROGRESS', 'BLOCKED'],
  IN_PROGRESS: ['DONE', 'BLOCKED'],
  BLOCKED: ['IN_PROGRESS'],
  DONE: [],
};

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

const OUTBOX_STATUS_COLOR: Record<OutboxStatus, string> = {
  PENDING: 'bg-yellow-100 text-yellow-700',
  PROCESSING: 'bg-blue-100 text-blue-700',
  SUCCESS: 'bg-green-100 text-green-700',
  FAILED: 'bg-red-100 text-red-700',
};

// ===== 유틸 =====

function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '-';
  return new Date(iso).toLocaleString('ko-KR', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

// datetime-local input용 (초 없이 "yyyy-MM-ddTHH:mm")
function toDateTimeLocal(iso: string | null | undefined): string {
  if (!iso) return '';
  return iso.slice(0, 16); // "yyyy-MM-ddTHH:mm:ss" → "yyyy-MM-ddTHH:mm"
}

// ===== 수정 폼 =====

interface EditFormProps {
  initialTitle: string;
  initialDescription: string | null;
  initialDueAt: string | null;
  initialCalendarSync: boolean;
  onSubmit: (data: TaskUpdateRequest) => void;
  onCancel: () => void;
  isPending: boolean;
  isError: boolean;
}

function EditForm({
  initialTitle,
  initialDescription,
  initialDueAt,
  initialCalendarSync,
  onSubmit,
  onCancel,
  isPending,
  isError,
}: EditFormProps) {
  const [title, setTitle] = useState(initialTitle);
  const [description, setDescription] = useState(initialDescription ?? '');
  const [dueAt, setDueAt] = useState(toDateTimeLocal(initialDueAt));
  const [calendarSyncEnabled, setCalendarSyncEnabled] = useState(initialCalendarSync);
  const [titleError, setTitleError] = useState('');
  const [dueAtError, setDueAtError] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    let valid = true;

    if (!title.trim()) {
      setTitleError('제목을 입력해주세요.');
      valid = false;
    }

    if (calendarSyncEnabled && !dueAt) {
      setDueAtError('캘린더 동기화를 사용하려면 마감일이 필요합니다.');
      valid = false;
    }

    if (!valid) return;

    onSubmit({
      title: title.trim(),
      description: description.trim() || undefined,
      dueAt: dueAt ? `${dueAt}:00` : undefined,
      calendarSyncEnabled,
    });
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {/* 제목 */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          제목 <span className="text-red-500">*</span>
        </label>
        <input
          type="text"
          value={title}
          onChange={(e) => { setTitle(e.target.value); setTitleError(''); }}
          className={`w-full border rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 ${
            titleError ? 'border-red-400' : 'border-gray-300'
          }`}
        />
        {titleError && <p className="mt-1 text-xs text-red-500">{titleError}</p>}
      </div>

      {/* 설명 */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">설명</label>
        <textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          rows={3}
          className="w-full border border-gray-300 rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
        />
      </div>

      {/* 마감일 */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">마감일</label>
        <input
          type="datetime-local"
          value={dueAt}
          onChange={(e) => { setDueAt(e.target.value); setDueAtError(''); }}
          className={`w-full border rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 ${
            dueAtError ? 'border-red-400' : 'border-gray-300'
          }`}
        />
        {dueAtError && <p className="mt-1 text-xs text-red-500">{dueAtError}</p>}
      </div>

      {/* 캘린더 동기화 */}
      <div className="flex items-center gap-2">
        <input
          type="checkbox"
          id="editCalendarSync"
          checked={calendarSyncEnabled}
          onChange={(e) => { setCalendarSyncEnabled(e.target.checked); setDueAtError(''); }}
          className="w-4 h-4 accent-blue-600"
        />
        <label htmlFor="editCalendarSync" className="text-sm text-gray-700">
          Google Calendar 동기화
        </label>
      </div>

      {isError && (
        <div className="p-2 bg-red-50 border border-red-200 rounded text-red-700 text-sm">
          수정에 실패했습니다. 다시 시도해주세요.
        </div>
      )}

      <div className="flex gap-2 pt-1">
        <button
          type="submit"
          disabled={isPending}
          className="px-4 py-2 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
        >
          {isPending ? '저장 중...' : '저장'}
        </button>
        <button
          type="button"
          onClick={onCancel}
          disabled={isPending}
          className="px-4 py-2 text-sm border border-gray-300 text-gray-600 rounded hover:bg-gray-50 disabled:opacity-50"
        >
          취소
        </button>
      </div>
    </form>
  );
}

// ===== 메인 페이지 =====

export default function TaskDetailPage() {
  const { taskId } = useParams<{ taskId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const taskIdNum = Number(taskId);
  const [isEditing, setIsEditing] = useState(false);

  // Task 조회
  const {
    data: task,
    isLoading: taskLoading,
    isError: taskError,
  } = useQuery({
    queryKey: ['task', taskIdNum],
    queryFn: () => tasksApi.getTask(taskIdNum),
    enabled: !!taskIdNum,
  });

  // 캘린더 동기화 상태 조회
  const { data: syncStatus } = useQuery({
    queryKey: ['task-sync', taskIdNum],
    queryFn: () => tasksApi.getSyncStatus(taskIdNum),
    enabled: !!taskIdNum,
  });

  // 변경 이력 조회
  const { data: history } = useQuery({
    queryKey: ['task-history', taskIdNum],
    queryFn: () => tasksApi.getHistory(taskIdNum),
    enabled: !!taskIdNum,
  });

  // Task 수정
  const updateMutation = useMutation({
    mutationFn: (data: TaskUpdateRequest) => tasksApi.updateTask(taskIdNum, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['task', taskIdNum] });
      queryClient.invalidateQueries({ queryKey: ['task-sync', taskIdNum] });
      setIsEditing(false);
    },
  });

  // 상태 전이
  const changeStatusMutation = useMutation({
    mutationFn: (toStatus: TaskStatus) => tasksApi.changeStatus(taskIdNum, toStatus),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['task', taskIdNum] });
      queryClient.invalidateQueries({ queryKey: ['task-history', taskIdNum] });
    },
  });

  // 삭제
  const deleteMutation = useMutation({
    mutationFn: () => tasksApi.deleteTask(taskIdNum),
    onSuccess: () => {
      // 삭제 후 목록으로 이동 (projectId는 task에서 꺼냄)
      navigate(`/projects/${task?.projectId}/tasks`);
    },
  });

  const handleDelete = () => {
    if (!window.confirm('Task를 삭제하시겠습니까?')) return;
    deleteMutation.mutate();
  };

  // ===== 렌더링 =====

  if (taskLoading) {
    return (
      <div className="flex items-center justify-center h-40">
        <span className="text-gray-500">불러오는 중...</span>
      </div>
    );
  }

  if (taskError || !task) {
    return (
      <div className="p-4 bg-red-50 border border-red-200 rounded text-red-700">
        Task를 불러오지 못했습니다.
      </div>
    );
  }

  const nextStatuses = ALLOWED_TRANSITIONS[task.status];

  return (
    <div className="max-w-2xl mx-auto space-y-6">

      {/* 뒤로가기 */}
      <button
        onClick={() => navigate(`/projects/${task.projectId}/tasks`)}
        className="text-sm text-gray-400 hover:text-gray-600"
      >
        ← Task 목록
      </button>

      {/* Task 기본 정보 */}
      <div className="bg-white border border-gray-200 rounded-lg p-6">
        <div className="flex items-start justify-between mb-4">
          <div className="flex-1">
            {!isEditing && (
              <>
                <h2 className="text-xl font-semibold text-gray-800 mb-2">{task.title}</h2>
                <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLOR[task.status]}`}>
                  {STATUS_LABEL[task.status]}
                </span>
              </>
            )}
          </div>
          {!isEditing && (
            <div className="flex gap-2 ml-4">
              <button
                onClick={() => setIsEditing(true)}
                className="text-sm text-blue-600 hover:text-blue-800 border border-blue-300 rounded px-3 py-1"
              >
                수정
              </button>
              <button
                onClick={handleDelete}
                disabled={deleteMutation.isPending}
                className="text-sm text-red-500 hover:text-red-700 border border-red-300 rounded px-3 py-1 disabled:opacity-50"
              >
                삭제
              </button>
            </div>
          )}
        </div>

        {isEditing ? (
          <EditForm
            initialTitle={task.title}
            initialDescription={task.description}
            initialDueAt={task.dueAt}
            initialCalendarSync={task.calendarSyncEnabled}
            onSubmit={(data) => updateMutation.mutate(data)}
            onCancel={() => setIsEditing(false)}
            isPending={updateMutation.isPending}
            isError={updateMutation.isError}
          />
        ) : (
          <div className="space-y-3 mt-4">
            {task.description && (
              <div>
                <p className="text-xs text-gray-400 mb-1">설명</p>
                <p className="text-sm text-gray-700 whitespace-pre-wrap">{task.description}</p>
              </div>
            )}
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div>
                <p className="text-xs text-gray-400 mb-1">마감일</p>
                <p className="text-gray-700">{formatDateTime(task.dueAt)}</p>
              </div>
              <div>
                <p className="text-xs text-gray-400 mb-1">생성일</p>
                <p className="text-gray-700">{formatDateTime(task.createdAt)}</p>
              </div>
            </div>
          </div>
        )}

        {/* 상태 전이 버튼 */}
        {!isEditing && nextStatuses.length > 0 && (
          <div className="mt-4 pt-4 border-t border-gray-100">
            <p className="text-xs text-gray-400 mb-2">상태 변경</p>
            <div className="flex gap-2 flex-wrap">
              {nextStatuses.map((next) => (
                <button
                  key={next}
                  onClick={() => changeStatusMutation.mutate(next)}
                  disabled={changeStatusMutation.isPending}
                  className="px-3 py-1 text-sm border border-gray-300 rounded hover:bg-gray-50 disabled:opacity-50"
                >
                  → {STATUS_LABEL[next]}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* 캘린더 동기화 상태 */}
      <div className="bg-white border border-gray-200 rounded-lg p-6">
        <h3 className="text-base font-semibold text-gray-800 mb-4">캘린더 동기화</h3>

        {!task.calendarSyncEnabled ? (
          <p className="text-sm text-gray-400">동기화 비활성화 상태입니다.</p>
        ) : (
          <div className="space-y-3 text-sm">
            <div className="flex items-center gap-2">
              <p className="text-xs text-gray-400 w-24">동기화 상태</p>
              {syncStatus?.lastOutboxStatus ? (
                <span className={`px-2 py-0.5 rounded text-xs font-medium ${OUTBOX_STATUS_COLOR[syncStatus.lastOutboxStatus]}`}>
                  {OUTBOX_STATUS_LABEL[syncStatus.lastOutboxStatus]}
                </span>
              ) : (
                <span className="text-gray-400">없음</span>
              )}
            </div>

            <div className="flex items-center gap-2">
              <p className="text-xs text-gray-400 w-24">캘린더 이벤트</p>
              <p className="text-gray-700">
                {task.calendarEventId ? (
                  <span className="text-green-600">연동됨 ({task.calendarEventId.slice(0, 12)}...)</span>
                ) : (
                  <span className="text-yellow-600">미연동</span>
                )}
              </p>
            </div>

            <div className="flex items-center gap-2">
              <p className="text-xs text-gray-400 w-24">마지막 성공</p>
              <p className="text-gray-700">{formatDateTime(syncStatus?.lastSyncedAt)}</p>
            </div>

            {syncStatus?.lastOutboxStatus === 'FAILED' && syncStatus.lastOutboxError && (
              <div className="mt-2 p-2 bg-red-50 border border-red-200 rounded text-xs text-red-700">
                <p className="font-medium mb-1">오류 내용</p>
                <p className="font-mono break-all">{syncStatus.lastOutboxError}</p>
              </div>
            )}
          </div>
        )}
      </div>

      {/* 변경 이력 */}
      <div className="bg-white border border-gray-200 rounded-lg p-6">
        <h3 className="text-base font-semibold text-gray-800 mb-4">변경 이력</h3>

        {!history || history.length === 0 ? (
          <p className="text-sm text-gray-400">변경 이력이 없습니다.</p>
        ) : (
          <div className="divide-y divide-gray-100">
            {history.map((h, idx) => (
              <div key={idx} className="py-3 first:pt-0 last:pb-0">
                <div className="flex items-center justify-between mb-1">
                  <span className="text-sm font-medium text-gray-700">
                    {CHANGE_TYPE_LABEL[h.changeType] ?? h.changeType}
                  </span>
                  <span className="text-xs text-gray-400">{formatDateTime(h.createdAt)}</span>
                </div>
                {h.beforeValue || h.afterValue ? (
                  <div className="flex items-start gap-2 mt-1 mb-1 text-xs">
                    <span
                      className="flex-1 px-2 py-1 rounded break-all"
                      style={{ backgroundColor: '#f3f4f6', color: '#6b7280' }}
                    >
                      {h.beforeValue ?? '-'}
                    </span>
                    <span className="flex-shrink-0 mt-1" style={{ color: '#9ca3af' }}>→</span>
                    <span
                      className="flex-1 px-2 py-1 rounded font-medium break-all"
                      style={{ backgroundColor: '#eff6ff', color: '#1d4ed8' }}
                    >
                      {h.afterValue ?? '-'}
                    </span>
                  </div>
                ) : null}
                <p className="text-xs text-gray-400">{h.changedByUserName}</p>
              </div>
            ))}
          </div>
        )}
      </div>

    </div>
  );
}
