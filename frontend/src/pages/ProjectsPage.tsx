import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { Plus } from 'lucide-react';
import { projectsApi } from '@/api/endpoints/projects';
import type { ProjectCreateRequest } from '@/types/project';
import { cx, clsx } from '@/styles/cx';

export default function ProjectsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [name, setName] = useState('');
  const [nameError, setNameError] = useState('');

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

  const handleOpenModal = () => { setName(''); setNameError(''); setIsModalOpen(true); };
  const handleCloseModal = () => { setIsModalOpen(false); setName(''); setNameError(''); };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) { setNameError('프로젝트 이름을 입력해주세요.'); return; }
    createMutation.mutate({ name: trimmed });
  };

  if (isLoading) return (
    <div className="flex items-center justify-center h-40">
      <span className={cx.text.meta}>로딩 중...</span>
    </div>
  );

  if (isError) return <div className={cx.errorBox}>프로젝트 목록을 불러오지 못했습니다.</div>;

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
