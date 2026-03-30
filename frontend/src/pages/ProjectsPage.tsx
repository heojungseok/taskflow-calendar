import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { LoaderCircle, Plus, Search } from 'lucide-react';
import { projectsApi } from '@/api/endpoints/projects';
import type { ProjectCreateRequest, ProjectTaskSearchIntent, ProjectTaskSearchResponse } from '@/types/project';
import { cx, clsx } from '@/styles/cx';

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
      setSearchError('검색에 실패했습니다. 잠시 후 다시 시도해주세요.');
    },
  });

  const handleOpenModal = () => { setName(''); setNameError(''); setIsModalOpen(true); };
  const handleCloseModal = () => { setIsModalOpen(false); setName(''); setNameError(''); };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) { setNameError('프로젝트 이름을 입력해주세요.'); return; }
    createMutation.mutate({ name: trimmed });
  };

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = searchQuery.trim();
    if (!trimmed) {
      setSearchError('검색어를 입력해주세요.');
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

  if (isLoading) return (
    <div className="flex items-center justify-center h-40">
      <span className={cx.text.meta}>로딩 중...</span>
    </div>
  );

  if (isError) return <div className={cx.errorBox}>프로젝트 목록을 불러오지 못했습니다.</div>;
  const fallbackSuggestions = searchResult
    ? buildSuggestedQueries(searchResult.intent, searchResult.query, searchResult.suggestedQueries)
    : [];

  return (
    <div>
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-5">
        <h2 className={cx.text.heading}>프로젝트</h2>
        <button onClick={handleOpenModal} className={cx.btn.primary}>
          <span className="flex items-center gap-1.5">
            <Plus size={12} strokeWidth={2.5} />
            새 프로젝트
          </span>
        </button>
      </div>

      <form onSubmit={handleSearchSubmit} className="mb-5">
        <div className="flex flex-col gap-2 sm:flex-row">
          <div className="relative flex-1">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-[#6b7280]" />
            <input
              value={searchQuery}
              onChange={(e) => {
                setSearchQuery(e.target.value);
                setSearchError('');
              }}
              placeholder="예: 이번 주 배포 준비 일정, 병원 가는 일정"
              className={clsx(cx.input, 'pl-9')}
            />
          </div>
          <button type="submit" className={cx.btn.primary} disabled={searchMutation.isPending}>
            <span className="flex items-center gap-2">
              {searchMutation.isPending && <LoaderCircle size={14} className="animate-spin" />}
              {searchMutation.isPending ? '검색 중...' : '검색하기'}
            </span>
          </button>
        </div>
        {searchError && <div className={clsx(cx.errorBox, 'mt-3')}>{searchError}</div>}
      </form>

      {searchMutation.isPending && (
        <div className="mb-6 space-y-4">
          <div className={clsx(cx.card, 'p-4')}>
            <div className="flex items-center gap-2">
              <LoaderCircle size={16} className="animate-spin text-[#3b5bff]" />
              <div>
                <p className={clsx(cx.text.subheading, 'mb-1')}>검색 중</p>
                <p className={cx.text.meta}>질의를 해석하고 관련 일정을 찾고 있습니다.</p>
              </div>
            </div>
          </div>

          <div className={clsx(cx.card, 'p-4')}>
            <div className="mb-4 h-4 w-24 animate-pulse rounded-full bg-[#1f2333]" />
            <div className="space-y-3">
              {[0, 1, 2].map((index) => (
                <div key={index} className="rounded-[18px] border border-[#232331] bg-[#11111a] p-4">
                  <div className="mb-3 h-4 w-2/5 animate-pulse rounded-full bg-[#1f2333]" />
                  <div className="mb-2 h-3 w-3/5 animate-pulse rounded-full bg-[#181c28]" />
                  <div className="h-3 w-1/4 animate-pulse rounded-full bg-[#181c28]" />
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {searchResult && (
        <div className="mb-6 space-y-4">
          {searchResult.fallback ? (
            <div className={clsx(cx.card, 'p-4')}>
              <p className={clsx(cx.text.subheading, 'mb-2')}>질의가 아직 조금 넓습니다</p>
              <p className={clsx(cx.text.meta, 'mb-3')}>
                더 구체적인 검색어로 다시 시도하면 더 정확한 결과를 보여줄 수 있습니다.
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
              <div className={clsx(cx.card, 'p-4')}>
                <p className={clsx(cx.text.subheading, 'mb-3')}>일정 결과</p>
                {searchResult.taskResults.length === 0 ? (
                  <div>
                    <p className={cx.text.meta}>조건에 맞는 일정이 없습니다.</p>
                    {fallbackSuggestions.length > 0 && (
                      <div className="mt-4">
                        <p className={clsx(cx.text.meta, 'mb-2')}>다른 조건으로 다시 찾아보기</p>
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
                  <div className="space-y-2">
                    {searchResult.taskResults.map((task) => (
                      <button
                        key={task.taskId}
                        type="button"
                        onClick={() => navigate(`/projects/${task.projectId}/tasks`)}
                        className={clsx(cx.cardInteractive, 'w-full text-left')}
                      >
                        <div className="flex items-start justify-between gap-3">
                          <div>
                            <p className={cx.text.cardTitle}>{task.title}</p>
                            <p className={clsx(cx.text.meta, 'mt-1')}>
                              {task.projectName} · {task.status} · {task.syncState}
                            </p>
                          </div>
                        </div>
                        {task.dueAt && (
                          <p className={clsx(cx.text.meta, 'mt-2')}>
                            마감 {new Date(task.dueAt).toLocaleDateString('ko-KR')}
                          </p>
                        )}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              {searchResult.relatedProjects.length > 1 && (
                <div className={clsx(cx.card, 'p-4')}>
                  <p className={clsx(cx.text.subheading, 'mb-3')}>관련 프로젝트</p>
                  <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
                    {searchResult.relatedProjects.map((project) => (
                      <button
                        key={project.projectId}
                        type="button"
                        onClick={() => navigate(`/projects/${project.projectId}/tasks`)}
                        className={clsx(cx.cardInteractive, 'text-left w-full')}
                      >
                        <p className={cx.text.cardTitle}>{project.projectName}</p>
                        <p className={clsx(cx.text.meta, 'mt-2')}>
                          매칭 일정 {project.matchedTaskCount}개
                        </p>
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* 목록 */}
      {projects && projects.length === 0 ? (
        <div className={cx.emptyState}>
          <p className="text-[13px] mb-1">프로젝트가 없습니다</p>
          <p className="text-[11px]">새 프로젝트를 만들어 시작하세요</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
          <AnimatePresence>
            {projects?.map((project) => (
              <motion.button
                key={project.id}
                layout
                initial={{ opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.97 }}
                transition={{ duration: 0.12 }}
                onClick={() => navigate(`/projects/${project.id}/tasks`)}
                className={clsx(cx.cardInteractive, 'text-left w-full group')}
              >
                <div className="flex items-center justify-between">
                  <p className={clsx(cx.text.cardTitle, 'group-hover:text-[#e8e8ed] transition-colors duration-150')}>
                    {project.name}
                  </p>
                  <span className="text-[#2a2a3a] group-hover:text-[#3b5bff]/40 transition-colors text-base leading-none">›</span>
                </div>
                <p className={clsx(cx.text.meta, 'mt-2')}>
                  {new Date(project.createdAt).toLocaleDateString('ko-KR')}
                </p>
              </motion.button>
            ))}
          </AnimatePresence>
        </div>
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
              initial={{ opacity: 0, scale: 0.97, y: 6 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.97 }}
              transition={{ duration: 0.12 }}
            >
              <h3 className={clsx(cx.text.subheading, 'mb-5')}>새 프로젝트</h3>

              <form onSubmit={handleSubmit}>
                <div className="mb-5">
                  <label className={cx.text.label}>이름</label>
                  <input
                    type="text"
                    value={name}
                    onChange={(e) => { setName(e.target.value); setNameError(''); }}
                    placeholder="프로젝트 이름"
                    className={clsx(cx.input, nameError && cx.inputError)}
                    autoFocus
                  />
                  {nameError && <p className="mt-1.5 text-[11px] text-[#ff6b6b]">{nameError}</p>}
                </div>

                {createMutation.isError && (
                  <div className={clsx(cx.errorBox, 'mb-4')}>생성에 실패했습니다.</div>
                )}

                <div className="flex justify-end gap-2">
                  <button type="button" onClick={handleCloseModal} disabled={createMutation.isPending} className={cx.btn.secondary}>
                    취소
                  </button>
                  <button type="submit" disabled={createMutation.isPending} className={cx.btn.primary}>
                    {createMutation.isPending ? '생성 중...' : '생성'}
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
