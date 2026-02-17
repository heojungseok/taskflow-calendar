import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { projectsApi } from '@/api/endpoints/projects';
import type { ProjectCreateRequest } from '@/types/project';

export default function ProjectsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [name, setName] = useState('');
  const [nameError, setNameError] = useState('');

  // 프로젝트 목록 조회
  const { data: projects, isLoading, isError } = useQuery({
    queryKey: ['projects'],
    queryFn: projectsApi.getProjects,
  });

  // 프로젝트 생성
  const createMutation = useMutation({
    mutationFn: (data: ProjectCreateRequest) => projectsApi.createProject(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['projects'] });
      handleCloseModal();
    },
  });

  const handleOpenModal = () => {
    setName('');
    setNameError('');
    setIsModalOpen(true);
  };

  const handleCloseModal = () => {
    setIsModalOpen(false);
    setName('');
    setNameError('');
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    const trimmed = name.trim();
    if (!trimmed) {
      setNameError('프로젝트 이름을 입력해주세요.');
      return;
    }

    createMutation.mutate({ name: trimmed });
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
        프로젝트 목록을 불러오지 못했습니다.
      </div>
    );
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-xl font-semibold text-gray-800">프로젝트</h2>
        <button
          onClick={handleOpenModal}
          className="bg-blue-600 text-white px-4 py-2 rounded hover:bg-blue-700 text-sm font-medium transition-colors"
        >
          + 새 프로젝트
        </button>
      </div>

      {/* 프로젝트 목록 */}
      {projects && projects.length === 0 ? (
        <div className="text-center py-16 text-gray-400">
          <p className="text-lg mb-2">프로젝트가 없습니다.</p>
          <p className="text-sm">새 프로젝트를 만들어 시작하세요.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {projects?.map((project) => (
            <button
              key={project.id}
              onClick={() => navigate(`/projects/${project.id}/tasks`)}
              className="text-left p-4 bg-white border border-gray-200 rounded-lg hover:border-blue-400 hover:shadow-sm transition-all group"
            >
              <p className="font-medium text-gray-800 group-hover:text-blue-600 truncate">
                {project.name}
              </p>
              <p className="text-xs text-gray-400 mt-1">
                {new Date(project.createdAt).toLocaleDateString('ko-KR')} 생성
              </p>
            </button>
          ))}
        </div>
      )}

      {/* 생성 모달 */}
      {isModalOpen && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg shadow-xl p-6 w-full max-w-md mx-4">
            <h3 className="text-lg font-semibold text-gray-800 mb-4">새 프로젝트</h3>

            <form onSubmit={handleSubmit}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  프로젝트 이름 <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={name}
                  onChange={(e) => {
                    setName(e.target.value);
                    if (nameError) setNameError('');
                  }}
                  placeholder="예: TaskFlow 개발"
                  className={`w-full border rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 ${
                    nameError ? 'border-red-400' : 'border-gray-300'
                  }`}
                  autoFocus
                />
                {nameError && (
                  <p className="mt-1 text-xs text-red-500">{nameError}</p>
                )}
              </div>

              {/* 뮤테이션 에러 */}
              {createMutation.isError && (
                <div className="mb-4 p-2 bg-red-50 border border-red-200 rounded text-red-700 text-sm">
                  프로젝트 생성에 실패했습니다. 다시 시도해주세요.
                </div>
              )}

              <div className="flex justify-end gap-2">
                <button
                  type="button"
                  onClick={handleCloseModal}
                  disabled={createMutation.isPending}
                  className="px-4 py-2 text-sm text-gray-600 border border-gray-300 rounded hover:bg-gray-50 disabled:opacity-50"
                >
                  취소
                </button>
                <button
                  type="submit"
                  disabled={createMutation.isPending}
                  className="px-4 py-2 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {createMutation.isPending ? '생성 중...' : '생성'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
