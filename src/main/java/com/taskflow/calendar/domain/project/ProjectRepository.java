package com.taskflow.calendar.domain.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** 소유자 기준 조회. 남의 프로젝트는 empty로 떨어져 404가 된다(존재 여부를 흘리지 않는다). */
    java.util.Optional<Project> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    java.util.List<Project> findAllByOwnerUserId(Long ownerUserId);

}
