import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { LoaderCircle, Plus, Search } from 'lucide-react';
import { projectsApi } from '@/api/endpoints/projects';
import type { ProjectCreateRequest, ProjectTaskSearchIntent, ProjectTaskSearchResponse } from '@/types/project';
import { cx, clsx, SYNC_STATE, TASK_STATUS } from '@/styles/cx';

const ACTION_QUERY_LABELS: Record<ProjectTaskSearchIntent['mainAction'], string> = {
  PREPARE: '준비',
  SUBMIT: '제출',
  BUY: '구매',
  VISIT: '방문',
  MEET: '만남',
  ORGANIZE: '정리',
  FIX: '해결',
  CHECK: '확인',
  UNKNOWN: '일정',
};

function buildSuggestedQueries(intent: ProjectTaskSearchIntent, currentQuery: string, existing: string[]) {
  const suggestions = new Set<string>();

  existing.forEach((suggestion) => {
    if (suggestion.trim() && suggestion.trim() !== currentQuery.trim()) {
      suggestions.add(suggestion.trim());
    }
  });

  if (suggestions.size === 0) {
    const primaryTopic = intent.topicTerms[0];
    const primaryParticipant = intent.participantTerms[0];
    const primaryLocation = intent.locationTerms[0];
    const actionLabel = ACTION_QUERY_LABELS[intent.mainAction];

    if (primaryTopic) {
      suggestions.add(`이번 주 ${primaryTopic} 일정`);
      suggestions.add(`중요한 ${primaryTopic} 일정`);
      suggestions.add(`${primaryTopic} 관련 일정`);
    } else if (primaryParticipant && primaryLocation) {
      suggestions.add(`${primaryLocation} 관련 일정`);
      suggestions.add(`${primaryParticipant} 관련 일정`);
      suggestions.add(`이번 주 ${primaryLocation} ${actionLabel}`);
    } else if (primaryParticipant && intent.mainAction !== 'UNKNOWN') {
      suggestions.add(`${primaryParticipant} 관련 일정`);
      suggestions.add(`이번 주 ${primaryParticipant} 일정`);
      suggestions.add(`${actionLabel} 관련 일정`);
    } else if (primaryLocation && intent.mainAction !== 'UNKNOWN') {
      suggestions.add(`이번 주 ${primaryLocation} ${actionLabel}`);
      suggestions.add(`${primaryLocation} 관련 일정`);
      suggestions.add(`중요한 ${primaryLocation} 일정`);
    } else if (primaryLocation) {
      suggestions.add(`이번 주 ${primaryLocation} 일정`);
      suggestions.add(`${primaryLocation} 관련 일정`);
      suggestions.add(`중요한 ${primaryLocation} 일정`);
    } else if (primaryParticipant) {
      suggestions.add(`${primaryParticipant} 관련 일정`);
      suggestions.add(`이번 주 ${primaryParticipant} 일정`);
    } else if (intent.mainAction !== 'UNKNOWN') {
      suggestions.add(`이번 주 ${actionLabel} 일정`);
      suggestions.add(`중요한 ${actionLabel} 일정`);
      suggestions.add(`${actionLabel} 관련 일정`);
    } else {
      suggestions.add('이번 주 마감 일정');
      suggestions.add('중요한 일정');
      suggestions.add('캘린더 반영 안 된 일정');
    }
  }

  return Array.from(suggestions).slice(0, 3);
}

/** 동기화 상태 표시등 — 이 앱에서 색이 허용된 자리 */
function SyncDot({ state }: { state: keyof typeof SYNC_STATE }) {
  const meta = SYNC_STATE[state];
  if (!meta) return null;
  return (
    <span className="inline-flex items-center gap-1.5 text-[12px] text-[var(--ink-2)]">
      <span
        aria-hidden
        className="w-[7px] h-[7px] rounded-[1px] shrink-0"
        style={{ backgroundColor: meta.mark }}
      />
      {meta.ko}
    </span>
  );
}

function SkeletonLine({ w }: { w: string }) {
  return (
    <div
      className="h-3 animate-pulse rounded-[var(--radius)] bg-[var(--sunken)]"
      style={{ width: w }}
    />
  );
}

export default function ProjectsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [name, setName] = useState('');
  const [nameError, setNameError] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [searchError, setSearchError] = useState('');
  const [searchResult, setSearchResult] = useState<ProjectTaskSearchResponse | null>(null);

  const { data: projects, isLoading, isError } = useQuery({
    queryKey: ['projects'],
    queryFn: projectsApi.getProjects,
  });

  const createMutation = useMutation({
    mutationFn: (data: ProjectCreateRequest) => projectsApi.createProject(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['projects'] });
      handleCloseModal();
    },
  });

  const searchMutation = useMutation({
    mutationFn: (query: string) => projectsApi.searchTasks(query),
    onSuccess: (data) => {
      setSearchResult(data);
      setSearchError('');
    },
    onError: () => {
      setSearchError('검색하지 못했습니다. 잠시 후 다시 시도하세요.');
    },
  });

  const handleOpenModal = () => { setName(''); setNameError(''); setIsModalOpen(true); };
  const handleCloseModal = () => { setIsModalOpen(false); setName(''); setNameError(''); };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) { setNameError('프로젝트 이름을 입력하세요.'); return; }
    createMutation.mutate({ name: trimmed });
  };

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = searchQuery.trim();
    if (!trimmed) {
      setSearchError('찾을 내용을 입력하세요.');
      return;
    }
    setSearchResult(null);
    searchMutation.mutate(trimmed);
  };

  const handleSuggestedSearch = (query: string) => {
    setSearchQuery(query);
    setSearchError('');
    setSearchResult(null);
    searchMutation.mutate(query);
  };

  const fallbackSuggestions = searchResult
    ? buildSuggestedQueries(searchResult.intent, searchResult.query, searchResult.suggestedQueries)
    : [];

  return (
    <div>
      {/* 제목 */}
      <div className="flex items-center justify-between gap-4 mb-7">
        <h2 className={cx.text.heading}>프로젝트</h2>
        <button onClick={handleOpenModal} className={clsx(cx.btn.primary, 'inline-flex items-center gap-1.5')}>
          <Plus size={14} strokeWidth={2.5} />
          새 프로젝트
        </button>
      </div>

      {/* 검색 — 이 앱의 특징적인 기능이라 자리를 내준다 */}
      <section className="mb-8">
        <label htmlFor="task-search" className={cx.text.label}>
          자연어로 일정 찾기
        </label>
        <form onSubmit={handleSearchSubmit}>
          <div className="flex flex-col gap-2 sm:flex-row">
            <div className="relative flex-1">
              <Search
                size={15}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--ink-3)] pointer-events-none"
              />
              <input
                id="task-search"
                value={searchQuery}
                onChange={(e) => {
                  setSearchQuery(e.target.value);
                  setSearchError('');
                }}
                placeholder="이번 주 배포 준비 일정"
                className={clsx(cx.input, 'pl-9')}
              />
            </div>
            <button
              type="submit"
              className={clsx(cx.btn.primary, 'inline-flex items-center justify-center gap-2 shrink-0')}
              disabled={searchMutation.isPending}
            >
              {searchMutation.isPending && <LoaderCircle size={14} className="animate-spin" />}
              {searchMutation.isPending ? '찾는 중' : '찾기'}
            </button>
          </div>
          {searchError && <div className={clsx(cx.errorBox, 'mt-3')}>{searchError}</div>}
        </form>
      </section>

      {/* 검색 중 */}
      {searchMutation.isPending && (
        <div className={clsx(cx.card, 'mb-8')}>
          <p className={clsx(cx.text.subheading, 'mb-1')}>질의를 해석하는 중</p>
          <p className="text-[13px] text-[var(--ink-2)] mb-5">
            검색어의 의도를 파악하고 관련 일정을 찾고 있습니다.
          </p>
          <div className="space-y-4">
            {[0, 1, 2].map((i) => (
              <div key={i} className="space-y-2">
                <SkeletonLine w="40%" />
                <SkeletonLine w="24%" />
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 검색 결과 */}
      {searchResult && (
        <div className="mb-8 space-y-4">
          {!searchResult.intentFallback && searchResult.semanticStatus === 'UNAVAILABLE' && (
            <div className={clsx(cx.card, 'text-[13px] text-[var(--ink-2)]')}>
              의미 검색을 쓸 수 없어 키워드 검색 결과만 표시합니다.
            </div>
          )}
          {searchResult.intentFallback ? (
            <div className={cx.card}>
              <p className={clsx(cx.text.subheading, 'mb-1')}>검색어가 넓습니다</p>
              <p className="text-[13px] text-[var(--ink-2)] mb-4">
                조금 더 구체적으로 적으면 정확한 결과를 찾을 수 있습니다.
              </p>
              <div className="flex flex-wrap gap-2">
                {searchResult.suggestedQueries.map((suggestion) => (
                  <button
                    key={suggestion}
                    type="button"
                    onClick={() => handleSuggestedSearch(suggestion)}
                    className={cx.btn.secondary}
                  >
                    {suggestion}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <>
              <section>
                <div className="flex items-baseline justify-between mb-2">
                  <span className={clsx(cx.text.label, 'mb-0')}>검색 결과</span>
                  <span className={cx.text.data}>{searchResult.taskResults.length}건</span>
                </div>

                {searchResult.taskResults.length === 0 ? (
                  <div className="border-t border-[var(--rule)] pt-5">
                    <p className="text-[14px] text-[var(--ink-2)]">조건에 맞는 일정이 없습니다.</p>
                    {fallbackSuggestions.length > 0 && (
                      <div className="mt-4">
                        <p className={cx.text.label}>다른 조건으로 찾기</p>
                        <div className="flex flex-wrap gap-2">
                          {fallbackSuggestions.map((suggestion) => (
                            <button
                              key={suggestion}
                              type="button"
                              onClick={() => handleSuggestedSearch(suggestion)}
                              className={cx.btn.secondary}
                            >
                              {suggestion}
                            </button>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                ) : (
                  <ul className="border-t border-[var(--rule)]">
                    {searchResult.taskResults.map((task) => (
                      <li key={task.taskId} className="border-b border-[var(--rule)]">
                        <button
                          type="button"
                          onClick={() => navigate(`/projects/${task.projectId}/tasks`)}
                          className="w-full text-left px-2 py-3.5 -mx-2 hover:bg-[var(--sunken)] transition-colors duration-150"
                        >
                          <div className="flex items-baseline justify-between gap-4">
                            <p className="text-[15px] text-[var(--ink)]">{task.title}</p>
                            {task.dueAt && (
                              <span className={clsx(cx.text.data, 'shrink-0')}>
                                {new Date(task.dueAt).toLocaleDateString('ko-KR')}
                              </span>
                            )}
                          </div>
                          <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1">
                            <span className="text-[12px] text-[var(--ink-3)]">{task.projectName}</span>
                            <span className="text-[12px] text-[var(--ink-3)]">
                              {TASK_STATUS[task.status]}
                            </span>
                            <SyncDot state={task.syncState} />
                          </div>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </section>

              {searchResult.relatedProjects.length > 1 && (
                <section>
                  <p className={cx.text.label}>관련 프로젝트</p>
                  <div className="flex flex-wrap gap-2">
                    {searchResult.relatedProjects.map((project) => (
                      <button
                        key={project.projectId}
                        type="button"
                        onClick={() => navigate(`/projects/${project.projectId}/tasks`)}
                        className={clsx(cx.btn.secondary, 'inline-flex items-center gap-2')}
                      >
                        {project.projectName}
                        <span className="font-mono text-[11px] text-[var(--ink-3)] tabular">
                          {project.matchedTaskCount}
                        </span>
                      </button>
                    ))}
                  </div>
                </section>
              )}
            </>
          )}
        </div>
      )}

      {/* 목록 */}
      {isLoading ? (
        <div className="border-t border-[var(--rule)] pt-5 space-y-5">
          {[0, 1, 2].map((i) => (
            <SkeletonLine key={i} w={['45%', '30%', '38%'][i]} />
          ))}
        </div>
      ) : isError ? (
        <div className={cx.errorBox}>프로젝트 목록을 불러오지 못했습니다.</div>
      ) : projects && projects.length === 0 ? (
        <div className="border-t border-[var(--rule)] pt-10 pb-6">
          <p className="text-[15px] text-[var(--ink)] mb-1">첫 프로젝트를 만들어 시작하세요.</p>
          <p className="text-[13px] text-[var(--ink-2)] mb-5">
            프로젝트 안에 일정을 넣으면 구글 캘린더로 반영됩니다.
          </p>
          <button onClick={handleOpenModal} className={clsx(cx.btn.primary, 'inline-flex items-center gap-1.5')}>
            <Plus size={14} strokeWidth={2.5} />
            새 프로젝트
          </button>
        </div>
      ) : (
        <ul className="border-t border-[var(--rule)]">
          <AnimatePresence initial={false}>
            {projects?.map((project) => (
              <motion.li
                key={project.id}
                layout
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.14 }}
                className="border-b border-[var(--rule)]"
              >
                <button
                  onClick={() => navigate(`/projects/${project.id}/tasks`)}
                  className="group w-full text-left px-2 py-4 -mx-2 flex items-baseline justify-between gap-4 hover:bg-[var(--sunken)] transition-colors duration-150"
                >
                  <span className="text-[15px] text-[var(--ink)]">{project.name}</span>
                  <span className="flex items-baseline gap-3 shrink-0">
                    <span className={cx.text.data}>
                      {new Date(project.createdAt).toLocaleDateString('ko-KR')}
                    </span>
                    <span
                      aria-hidden
                      className="text-[var(--ink-3)] transition-transform duration-150 group-hover:translate-x-0.5"
                    >
                      →
                    </span>
                  </span>
                </button>
              </motion.li>
            ))}
          </AnimatePresence>
        </ul>
      )}

      {/* 생성 모달 */}
      <AnimatePresence>
        {isModalOpen && (
          <motion.div
            className={cx.overlay}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.12 }}
          >
            <motion.div
              className={cx.modal}
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.14 }}
            >
              <h3 className={clsx(cx.text.subheading, 'mb-5')}>새 프로젝트</h3>

              <form onSubmit={handleSubmit}>
                <div className="mb-5">
                  <label htmlFor="project-name" className={cx.text.label}>이름</label>
                  <input
                    id="project-name"
                    type="text"
                    value={name}
                    onChange={(e) => { setName(e.target.value); setNameError(''); }}
                    placeholder="예: 개발 스프린트"
                    className={clsx(cx.input, nameError && cx.inputError)}
                    autoFocus
                  />
                  {nameError && (
                    <p className="mt-1.5 text-[12px] text-[var(--st-failed)]">{nameError}</p>
                  )}
                </div>

                {createMutation.isError && (
                  <div className={clsx(cx.errorBox, 'mb-4')}>
                    프로젝트를 만들지 못했습니다. 다시 시도하세요.
                  </div>
                )}

                <div className="flex justify-end gap-2">
                  <button type="button" onClick={handleCloseModal} disabled={createMutation.isPending} className={cx.btn.secondary}>
                    취소
                  </button>
                  <button type="submit" disabled={createMutation.isPending} className={cx.btn.primary}>
                    {createMutation.isPending ? '만드는 중' : '만들기'}
                  </button>
                </div>
              </form>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
