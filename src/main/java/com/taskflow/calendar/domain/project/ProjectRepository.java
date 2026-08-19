package com.taskflow.calendar.domain.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** 소유자 기준 조회. 남의 프로젝트는 empty로 떨어져 404가 된다(존재 여부를 흘리지 않는다). */
    java.util.Optional<Project> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    java.util.List<Project> findAllByOwnerUserId(Long ownerUserId);

    long countByOwnerUserId(Long ownerUserId);

    @Modifying
    @Query("delete from Project p where p.ownerUserId = :userId")
    int deleteOwnedBy(@Param("userId") Long userId);

}
