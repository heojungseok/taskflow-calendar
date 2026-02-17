import { useState } from 'react';
import { useQuery, useMutation } from '@tanstack/react-query';
import { outboxApi } from '@/api/endpoints/calendar';
import type { OutboxEntry, OutboxStatus, OutboxOpType } from '@/types/outbox';

// ===== 상수 =====

const STATUS_LABEL: Record<OutboxStatus, string> = {
  PENDING: '대기',
  PROCESSING: '처리 중',
  SUCCESS: '성공',
  FAILED: '실패',
};

const STATUS_STYLE: Record<OutboxStatus, { backgroundColor: string; color: string }> = {
  PENDING:    { backgroundColor: '#fef9c3', color: '#854d0e' },
  PROCESSING: { backgroundColor: '#dbeafe', color: '#1e40af' },
  SUCCESS:    { backgroundColor: '#dcfce7', color: '#166534' },
  FAILED:     { backgroundColor: '#fee2e2', color: '#991b1b' },
};

const OP_TYPE_LABEL: Record<OutboxOpType, string> = {
  UPSERT: 'UPSERT',
  DELETE: 'DELETE',
};

const OP_TYPE_STYLE: Record<OutboxOpType, { backgroundColor: string; color: string }> = {
  UPSERT: { backgroundColor: '#eff6ff', color: '#1d4ed8' },
  DELETE: { backgroundColor: '#fef2f2', color: '#b91c1c' },
};

const STATUS_FILTERS: Array<{ label: string; value: string }> = [
  { label: '전체', value: '' },
  { label: '대기', value: 'PENDING' },
  { label: '처리 중', value: 'PROCESSING' },
  { label: '성공', value: 'SUCCESS' },
  { label: '실패', value: 'FAILED' },
];

// ===== 유틸 =====

function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '-';
  return new Date(iso).toLocaleString('ko-KR', {
    month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

// ===== Outbox 행 =====

interface OutboxRowProps {
  entry: OutboxEntry;
}

function OutboxRow({ entry }: OutboxRowProps) {
  const [expanded, setExpanded] = useState(false);

  // payload JSON 파싱 시도
  let payloadFormatted = entry.payload;
  try {
    payloadFormatted = JSON.stringify(JSON.parse(entry.payload), null, 2);
  } catch {
    // 파싱 실패 시 원본 그대로
  }

  return (
    <div className="border border-gray-100 rounded-lg overflow-hidden">
      {/* 메인 행 */}
      <div
        className="flex items-center gap-3 px-4 py-3 cursor-pointer hover:bg-gray-50"
        onClick={() => setExpanded((v) => !v)}
      >
        {/* ID */}
        <span className="text-xs text-gray-400 w-8 flex-shrink-0">#{entry.id}</span>

        {/* opType */}
        <span
          className="text-xs font-medium px-2 py-0.5 rounded w-16 text-center flex-shrink-0"
          style={OP_TYPE_STYLE[entry.opType]}
        >
          {OP_TYPE_LABEL[entry.opType]}
        </span>

        {/* status */}
        <span
          className="text-xs font-medium px-2 py-0.5 rounded w-16 text-center flex-shrink-0"
          style={STATUS_STYLE[entry.status]}
        >
          {STATUS_LABEL[entry.status]}
        </span>

        {/* taskId */}
        <span className="text-xs text-gray-500 flex-shrink-0">
          Task #{entry.taskId}
        </span>

        {/* retryCount */}
        <span className="text-xs text-gray-400 flex-shrink-0">
          재시도 {entry.retryCount}회
        </span>

        {/* 생성 시각 */}
        <span className="text-xs text-gray-400 ml-auto flex-shrink-0">
          {formatDateTime(entry.createdAt)}
        </span>

        {/* 토글 화살표 */}
        <span className="text-gray-300 flex-shrink-0 text-xs">
          {expanded ? '▲' : '▼'}
        </span>
      </div>

      {/* 상세 펼침 */}
      {expanded && (
        <div className="px-4 pb-4 pt-1 border-t border-gray-100 bg-gray-50 space-y-3">
          {/* 에러 */}
          {entry.lastError && (
            <div>
              <p className="text-xs font-medium text-red-600 mb-1">오류 내용</p>
              <p
                className="text-xs font-mono break-all px-2 py-1 rounded"
                style={{ backgroundColor: '#fee2e2', color: '#991b1b' }}
              >
                {entry.lastError}
              </p>
            </div>
          )}

          {/* 다음 재시도 */}
          {entry.nextRetryAt && (
            <div>
              <p className="text-xs text-gray-400">
                다음 재시도: <span className="text-gray-600">{formatDateTime(entry.nextRetryAt)}</span>
              </p>
            </div>
          )}

          {/* payload */}
          <div>
            <p className="text-xs font-medium text-gray-500 mb-1">Payload</p>
            <pre
              className="text-xs font-mono break-all whitespace-pre-wrap px-3 py-2 rounded border border-gray-200"
              style={{ backgroundColor: '#f9fafb', color: '#374151' }}
            >
              {payloadFormatted}
            </pre>
          </div>

          {/* updatedAt */}
          <p className="text-xs text-gray-400">
            최종 수정: {formatDateTime(entry.updatedAt)}
          </p>
        </div>
      )}
    </div>
  );
}

// ===== 메인 페이지 =====

export default function OutboxPage() {
  const [statusFilter, setStatusFilter] = useState('');
  const [taskIdInput, setTaskIdInput] = useState('');
  const [taskIdFilter, setTaskIdFilter] = useState<number | undefined>(undefined);
  const [workerMessage, setWorkerMessage] = useState('');

  const {
    data: entries,
    isLoading,
    isError,
    refetch,
  } = useQuery({
    queryKey: ['outbox', statusFilter, taskIdFilter],
    queryFn: () =>
      outboxApi.getOutboxList({
        status: statusFilter || undefined,
        taskId: taskIdFilter,
      }),
  });

  const triggerMutation = useMutation({
    mutationFn: () => outboxApi.triggerWorker(),
    onSuccess: () => {
      setWorkerMessage('Worker 실행 완료. 목록을 새로고침합니다.');
      refetch();
      setTimeout(() => setWorkerMessage(''), 3000);
    },
    onError: () => {
      setWorkerMessage('Worker 실행 실패.');
      setTimeout(() => setWorkerMessage(''), 3000);
    },
  });

  const handleTaskIdSearch = () => {
    const parsed = parseInt(taskIdInput, 10);
    setTaskIdFilter(isNaN(parsed) ? undefined : parsed);
  };

  const handleTaskIdClear = () => {
    setTaskIdInput('');
    setTaskIdFilter(undefined);
  };

  // 상태별 카운트
  const counts = entries?.reduce(
    (acc, e) => {
      acc[e.status] = (acc[e.status] ?? 0) + 1;
      return acc;
    },
    {} as Record<string, number>
  );

  // ===== 렌더링 =====

  return (
    <div>
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <h2 className="text-xl font-semibold text-gray-800">Outbox 모니터</h2>
          {entries && (
            <span className="text-sm text-gray-400">{entries.length}개</span>
          )}
        </div>
        <button
          onClick={() => triggerMutation.mutate()}
          disabled={triggerMutation.isPending}
          className="px-4 py-2 text-sm border border-gray-300 rounded hover:bg-gray-50 disabled:opacity-50"
        >
          {triggerMutation.isPending ? '실행 중...' : '▶ Worker 실행'}
        </button>
      </div>

      {/* Worker 피드백 */}
      {workerMessage && (
        <div
          className="mb-4 px-3 py-2 rounded text-sm"
          style={{ backgroundColor: '#f0fdf4', color: '#166534', border: '1px solid #bbf7d0' }}
        >
          {workerMessage}
        </div>
      )}

      {/* 상태별 카운트 요약 */}
      {counts && Object.keys(counts).length > 0 && (
        <div className="flex gap-2 mb-4 flex-wrap">
          {(Object.entries(counts) as [OutboxStatus, number][]).map(([status, count]) => (
            <span
              key={status}
              className="text-xs px-2 py-1 rounded font-medium"
              style={STATUS_STYLE[status]}
            >
              {STATUS_LABEL[status]} {count}
            </span>
          ))}
        </div>
      )}

      {/* 필터 영역 */}
      <div className="flex items-center gap-3 mb-4 flex-wrap">
        {/* 상태 필터 */}
        <div className="flex gap-1">
          {STATUS_FILTERS.map((f) => (
            <button
              key={f.value}
              onClick={() => setStatusFilter(f.value)}
              className="px-3 py-1 rounded text-sm transition-colors"
              style={
                statusFilter === f.value
                  ? { backgroundColor: '#2563eb', color: '#ffffff' }
                  : { backgroundColor: '#ffffff', color: '#4b5563', border: '1px solid #d1d5db' }
              }
            >
              {f.label}
            </button>
          ))}
        </div>

        {/* Task ID 검색 */}
        <div className="flex items-center gap-1 ml-auto">
          <input
            type="number"
            value={taskIdInput}
            onChange={(e) => setTaskIdInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleTaskIdSearch()}
            placeholder="Task ID"
            className="border border-gray-300 rounded px-2 py-1 text-sm w-24 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <button
            onClick={handleTaskIdSearch}
            className="px-3 py-1 text-sm border border-gray-300 rounded hover:bg-gray-50"
          >
            검색
          </button>
          {taskIdFilter && (
            <button
              onClick={handleTaskIdClear}
              className="px-2 py-1 text-sm text-gray-400 hover:text-gray-600"
            >
              ✕
            </button>
          )}
        </div>
      </div>

      {/* 목록 */}
      {isLoading ? (
        <div className="flex items-center justify-center h-40">
          <span className="text-gray-400">불러오는 중...</span>
        </div>
      ) : isError ? (
        <div
          className="p-4 rounded text-sm"
          style={{ backgroundColor: '#fee2e2', color: '#991b1b' }}
        >
          Outbox 목록을 불러오지 못했습니다.
        </div>
      ) : !entries || entries.length === 0 ? (
        <div className="text-center py-16 text-gray-400">
          <p>Outbox 항목이 없습니다.</p>
        </div>
      ) : (
        <div className="space-y-2">
          {entries.map((entry) => (
            <OutboxRow key={entry.id} entry={entry} />
          ))}
        </div>
      )}
    </div>
  );
}
