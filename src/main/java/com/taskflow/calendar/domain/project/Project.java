package com.taskflow.calendar.domain.project;

import com.taskflow.calendar.domain.user.User;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 소유 계정. 격리 단위는 프로젝트다 - Task는 프로젝트를 따라간다.
     * 기존 행 백필을 위해 스키마는 nullable이며, null이면 어떤 조회에도 걸리지 않는다.
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_projects_owner_user"))
    private User owner;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at",nullable = false)
    private LocalDateTime updatedAt;

    private Project(String name, Long ownerUserId) {
        this.name = name;
        this.ownerUserId = ownerUserId;
    }

    public static Project of(String name, Long ownerUserId) {
        return new Project(name, ownerUserId);
    }
}
