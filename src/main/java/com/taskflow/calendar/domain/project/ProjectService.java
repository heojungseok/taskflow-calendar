package com.taskflow.calendar.domain.project;

import com.taskflow.calendar.domain.project.dto.CreateProjectRequest;
import com.taskflow.calendar.domain.project.dto.ProjectResponse;
import com.taskflow.calendar.domain.project.exception.ProjectNotFoundException;
import com.taskflow.security.SecurityContextHelper;
import com.taskflow.calendar.domain.user.DemoUsageService;
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
    private final DemoUsageService demoUsageService;

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request) {
        Long userId = SecurityContextHelper.getCurrentUserId();
        demoUsageService.beforeProjectCreate(userId);
        Project project = Project.of(request.getName(), userId);
        Project savedProject = projectRepository.save(project);

        return ProjectResponse.from(savedProject);
    }

    public List<ProjectResponse> getAllProjects() {
        return projectRepository.findAllByOwnerUserId(SecurityContextHelper.getCurrentUserId())
                .stream()
                .map(ProjectResponse::from)
                .collect(Collectors.toList());
    }

    public ProjectResponse getProjectById(Long id) {
        Project project = projectRepository
                .findByIdAndOwnerUserId(id, SecurityContextHelper.getCurrentUserId())
                .orElseThrow(() -> new ProjectNotFoundException(id));

        return ProjectResponse.from(project);
    }
}
