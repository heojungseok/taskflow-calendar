package com.taskflow.planning.project;

import com.taskflow.common.BaseTimeEntity;
import com.taskflow.identity.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project extends BaseTimeEntity {

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
    @JoinColumn(
            name = "owner_user_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_projects_owner_user"))
    private User owner;

    private Project(String name, Long ownerUserId) {
        this.name = name;
        this.ownerUserId = ownerUserId;
    }

    public static Project of(String name, Long ownerUserId) {
        return new Project(name, ownerUserId);
    }
}
