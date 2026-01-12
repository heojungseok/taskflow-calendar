package com.taskflow.calendar.domain.project;

import com.taskflow.calendar.domain.project.dto.CreateProjectRequest;
import com.taskflow.calendar.domain.project.dto.ProjectResponse;
import com.taskflow.calendar.domain.project.exception.ProjectNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request) {
        // Project 생성 → 저장 → DTO 변환
        Project project = Project.of(request.getName());
        Project savedProject = projectRepository.save(project);

        return ProjectResponse.from(savedProject);
    }

    public List<ProjectResponse> getAllProjects() {
        return projectRepository.findAll()
                .stream()
                .map(ProjectResponse::from)
                .collect(Collectors.toList());
    }

    public ProjectResponse getProjectById(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException(id));

        return ProjectResponse.from(project);
    }
}