package com.taskflow.calendar.domain.task;

import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.user.User;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tasks")
@EntityListeners(AuditingEntityListener.class)
@Getter
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_user_id")
    private User assignee;

    private LocalDateTime startAt;

    private LocalDateTime dueAt;

    @Column(nullable = false)
    private Boolean calendarSyncEnabled = false;

    @Column(length = 100)
    private String calendarEventId;

    @Column(nullable = false)
    private Boolean deleted = false;

    private LocalDateTime deletedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at",nullable = false)
    private LocalDateTime updatedAt;

    protected Task() {}

    private Task(Project project, String title, String description,
                 User assignee, LocalDateTime startAt, LocalDateTime dueAt,
                 Boolean calendarSyncEnabled) {
        this.project = project;
        this.title = title;
        this.description = description;
        this.status = TaskStatus.REQUESTED;
        this.assignee = assignee;
        this.startAt = startAt;
        this.dueAt = dueAt;
        this.calendarSyncEnabled = calendarSyncEnabled != null ? calendarSyncEnabled : false;
    }

    public static Task createTask(Project project, String title, String description,
                                  User assignee, LocalDateTime startAt, LocalDateTime dueAt,
                                  Boolean calendarSyncEnabled) {
        return new Task(project, title, description, assignee,
                startAt, dueAt, calendarSyncEnabled);
    }

    /**
     * Task 정보 수정
     */
    public void update(String title, String description, User assignee,
                       LocalDateTime startAt, LocalDateTime dueAt,
                       Boolean calendarSyncEnabled) {
        if (title != null) {
            this.title = title;
        }
        if (description != null) {
            this.description = description;
        }
        if (assignee != null) {
            this.assignee = assignee;
        }
        if (startAt != null) {
            this.startAt = startAt;
        }
        if (dueAt != null) {
            this.dueAt = dueAt;
        }
        if (calendarSyncEnabled != null) {
            this.calendarSyncEnabled = calendarSyncEnabled;
        }
    }

    /**
     * Task 상태 변경
     */
    public void changeStatus(TaskStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * Soft Delete 처리
     */
    public void markAsDeleted() {
        this.deleted = true;
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * Calendar Event ID 설정
     */
    public void setCalendarEventId(String eventId) {
        this.calendarEventId = eventId;
    }

    /**
     * Calendar 동기화 활성 조건 확인
     */
    public boolean isCalendarSyncActive() {
        return Boolean.TRUE.equals(this.calendarSyncEnabled) && this.dueAt != null;
    }

}