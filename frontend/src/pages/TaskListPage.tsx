import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { tasksApi } from '@/api/endpoints/tasks';
import { projectsApi } from '@/api/endpoints/projects';
import type { Task, TaskStatus, TaskCreateRequest } from '@/types/task';

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

// 백엔드 도메인 규칙: 허용된 상태 전이
const ALLOWED_TRANSITIONS: Record<TaskStatus, TaskStatus[]> = {
  REQUESTED: ['IN_PROGRESS', 'BLOCKED'],
  IN_PROGRESS: ['DONE', 'BLOCKED'],
  BLOCKED: ['IN_PROGRESS'],
  DONE: [],
};

const STATUS_FILTERS: Array<{ label: string; value: string }> = [
  { label: '전체', value: '' },
  { label: '요청됨', value: 'REQUESTED' },
  { label: '진행 중', value: 'IN_PROGRESS' },
  { label: '완료', value: 'DONE' },
  { label: '차단됨', value: 'BLOCKED' },
];

// ===== 서브 컴포넌트 =====

interface StatusBadgeProps {
  status: TaskStatus;
}

function StatusBadge({ status }: StatusBadgeProps) {
  return (
    <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLOR[status]}`}>
      {STATUS_LABEL[status]}
    </span>
  );
}

interface CalendarBadgeProps {
  enabled: boolean;
  synced: boolean;
}

function CalendarBadge({ enabled, synced }: CalendarBadgeProps) {
  if (!enabled) return null;
  return (
    <span
      className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
        synced ? 'bg-purple-100 text-purple-700' : 'bg-yellow-100 text-yellow-700'
      }`}
    >
      {synced ? '📅 캘린더 연동' : '📅 동기화 대기'}
    </span>
  );
}

// ===== Task 카드 =====

interface TaskCardProps {
  task: Task;
  onStatusChange: (taskId: number, toStatus: TaskStatus) => void;
  onDelete: (taskId: number) => void;
  onClickDetail: (taskId: number) => void;
  isStatusChanging: boolean;
  isDeleting: boolean;
}

function TaskCard({
  task,
  onStatusChange,
  onDelete,
  onClickDetail,
  isStatusChanging,
  isDeleting,
}: TaskCardProps) {
  const nextStatuses = ALLOWED_TRANSITIONS[task.status];

  return (
    <div className="bg-white border border-gray-200 rounded-lg p-4 hover:shadow-sm transition-shadow">
      {/* 상단: 제목 + 배지 */}
      <div
        className="cursor-pointer"
        onClick={() => onClickDetail(task.id)}
      >
        <p className="font-medium text-gray-800 mb-2 hover:text-blue-600">
          {task.title}
        </p>
        <div className="flex items-center gap-2 flex-wrap">
          <StatusBadge status={task.status} />
          <CalendarBadge
            enabled={task.calendarSyncEnabled}
            synced={task.calendarEventId !== null}
          />
        </div>
        {task.dueAt && (
          <p className="text-xs text-gray-400 mt-2">
            마감: {new Date(task.dueAt).toLocaleDateString('ko-KR')}
          </p>
        )}
      </div>

      {/* 하단: 상태 전이 버튼 + 삭제 */}
      <div className="mt-3 pt-3 border-t border-gray-100 flex items-center justify-between">
        <div className="flex gap-1 flex-wrap">
          {nextStatuses.map((next) => (
            <button
              key={next}
              onClick={() => onStatusChange(task.id, next)}
              disabled={isStatusChanging}
              className="px-2 py-1 text-xs border border-gray-300 rounded hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              → {STATUS_LABEL[next]}
            </button>
          ))}
          {nextStatuses.length === 0 && (
            <span className="text-xs text-gray-400">상태 변경 불가</span>
          )}
        </div>

        <button
          onClick={() => onDelete(task.id)}
          disabled={isDeleting}
          className="text-xs text-red-400 hover:text-red-600 disabled:opacity-50 disabled:cursor-not-allowed ml-2"
        >
          삭제
        </button>
      </div>
    </div>
  );
}

// ===== Task 생성 모달 =====

interface CreateTaskModalProps {
  projectId: number;
  onClose: () => void;
  onSubmit: (data: TaskCreateRequest) => void;
  isPending: boolean;
  isError: boolean;
}

function CreateTaskModal({ onClose, onSubmit, isPending, isError }: CreateTaskModalProps) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [dueAt, setDueAt] = useState('');
  const [calendarSyncEnabled, setCalendarSyncEnabled] = useState(false);
  const [titleError, setTitleError] = useState('');
  const [dueAtError, setDueAtError] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    let valid = true;

    if (!title.trim()) {
      setTitleError('제목을 입력해주세요.');
      valid = false;
    }

    // calendarSyncEnabled=true면 dueAt 필수 (백엔드 도메인 규칙)
    if (calendarSyncEnabled && !dueAt) {
      setDueAtError('캘린더 동기화를 사용하려면 마감일이 필요합니다.');
      valid = false;
    }

    if (!valid) return;

    onSubmit({
      title: title.trim(),
      description: description.trim() || undefined,
      dueAt: dueAt ? `${dueAt}:00` : undefined,  // datetime-local은 초가 없어서 추가
      calendarSyncEnabled: calendarSyncEnabled || undefined,
    });
  };

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl p-6 w-full max-w-md mx-4">
        <h3 className="text-lg font-semibold text-gray-800 mb-4">새 Task</h3>

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
              placeholder="Task 제목"
              className={`w-full border rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 ${
                titleError ? 'border-red-400' : 'border-gray-300'
              }`}
              autoFocus
            />
            {titleError && <p className="mt-1 text-xs text-red-500">{titleError}</p>}
          </div>

          {/* 설명 */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">설명</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="선택 사항"
              rows={2}
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
              id="calendarSync"
              checked={calendarSyncEnabled}
              onChange={(e) => { setCalendarSyncEnabled(e.target.checked); setDueAtError(''); }}
              className="w-4 h-4 accent-blue-600"
            />
            <label htmlFor="calendarSync" className="text-sm text-gray-700">
              Google Calendar 동기화
            </label>
          </div>

          {/* API 에러 */}
          {isError && (
            <div className="p-2 bg-red-50 border border-red-200 rounded text-red-700 text-sm">
              Task 생성에 실패했습니다. 다시 시도해주세요.
            </div>
          )}

          {/* 버튼 */}
          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              disabled={isPending}
              className="px-4 py-2 text-sm text-gray-600 border border-gray-300 rounded hover:bg-gray-50 disabled:opacity-50"
            >
              취소
            </button>
            <button
              type="submit"
              disabled={isPending}
              className="px-4 py-2 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {isPending ? '생성 중...' : '생성'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ===== 메인 페이지 =====

export default function TaskListPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const projectIdNum = Number(projectId);
  const [statusFilter, setStatusFilter] = useState('');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [changingStatusId, setChangingStatusId] = useState<number | null>(null);

  // 프로젝트 정보 조회 (헤더에 이름 표시용)
  const { data: project } = useQuery({
    queryKey: ['project', projectIdNum],
    queryFn: () => projectsApi.getProject(projectIdNum),
    enabled: !!projectIdNum,
  });

  // Task 목록 조회
  const {
    data: tasks,
    isLoading,
    isError,
  } = useQuery({
    queryKey: ['tasks', projectIdNum, statusFilter],
    queryFn: () =>
      tasksApi.getTasks(projectIdNum, statusFilter ? { status: statusFilter } : undefined),
    enabled: !!projectIdNum,
  });

  // Task 생성
  const createMutation = useMutation({
    mutationFn: (data: TaskCreateRequest) => tasksApi.createTask(projectIdNum, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks', projectIdNum] });
      setIsModalOpen(false);
    },
  });

  // Task 상태 변경
  const changeStatusMutation = useMutation({
    mutationFn: ({ taskId, toStatus }: { taskId: number; toStatus: TaskStatus }) =>
      tasksApi.changeStatus(taskId, toStatus),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks', projectIdNum] });
      setChangingStatusId(null);
    },
    onError: () => {
      setChangingStatusId(null);
    },
  });

  // Task 삭제
  const deleteMutation = useMutation({
    mutationFn: (taskId: number) => tasksApi.deleteTask(taskId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks', projectIdNum] });
      setDeletingId(null);
    },
    onError: () => {
      setDeletingId(null);
    },
  });

  const handleStatusChange = (taskId: number, toStatus: TaskStatus) => {
    setChangingStatusId(taskId);
    changeStatusMutation.mutate({ taskId, toStatus });
  };

  const handleDelete = (taskId: number) => {
    if (!window.confirm('Task를 삭제하시겠습니까?')) return;
    setDeletingId(taskId);
    deleteMutation.mutate(taskId);
  };

  // ===== 렌더링 =====

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-40">
        <span className="text-gray-500">불러오는 중...</span>
      </div>
    );
  }

  if (isError) {
    return (
      <div className="p-4 bg-red-50 border border-red-200 rounded text-red-700">
        Task 목록을 불러오지 못했습니다.
      </div>
    );
  }

  return (
    <div>
      {/* 헤더 영역 */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/projects')}
            className="text-sm text-gray-400 hover:text-gray-600"
          >
            ← 프로젝트
          </button>
          <h2 className="text-xl font-semibold text-gray-800">
            {project?.name ?? '...'}
          </h2>
          <span className="text-sm text-gray-400">
            {tasks?.length ?? 0}개
          </span>
        </div>
        <button
          onClick={() => setIsModalOpen(true)}
          className="bg-blue-600 text-white px-4 py-2 rounded hover:bg-blue-700 text-sm font-medium transition-colors"
        >
          + 새 Task
        </button>
      </div>

      {/* 상태 필터 */}
      <div className="flex gap-2 mb-4 flex-wrap">
        {STATUS_FILTERS.map((f) => (
          <button
            key={f.value}
            onClick={() => setStatusFilter(f.value)}
            className={`px-3 py-1 rounded text-sm transition-colors ${
              statusFilter === f.value
                ? 'bg-blue-600 text-white'
                : 'bg-white border border-gray-300 text-gray-600 hover:bg-gray-50'
            }`}
          >
            {f.label}
          </button>
        ))}
      </div>

      {/* Task 목록 */}
      {tasks && tasks.length === 0 ? (
        <div className="text-center py-16 text-gray-400">
          <p className="text-lg mb-2">Task가 없습니다.</p>
          <p className="text-sm">새 Task를 추가해보세요.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {tasks?.map((task) => (
            <TaskCard
              key={task.id}
              task={task}
              onStatusChange={handleStatusChange}
              onDelete={handleDelete}
              onClickDetail={(id) => navigate(`/tasks/${id}`)}
              isStatusChanging={changingStatusId === task.id}
              isDeleting={deletingId === task.id}
            />
          ))}
        </div>
      )}

      {/* Task 생성 모달 */}
      {isModalOpen && (
        <CreateTaskModal
          projectId={projectIdNum}
          onClose={() => setIsModalOpen(false)}
          onSubmit={(data) => createMutation.mutate(data)}
          isPending={createMutation.isPending}
          isError={createMutation.isError}
        />
      )}
    </div>
  );
}
