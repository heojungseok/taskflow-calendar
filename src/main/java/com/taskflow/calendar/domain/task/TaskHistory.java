package com.taskflow.calendar.domain.task;

import com.taskflow.calendar.domain.user.User;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "task_history")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by_user_id", nullable = false)
    private User changedByUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 30)
    private TaskChangeType changeType;

    @Column(name = "before_value", columnDefinition = "TEXT")
    private String beforeValue;

    @Column(name = "after_value", columnDefinition = "TEXT")
    private String afterValue;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private TaskHistory(
            Task task,
            User changedByUser,
            TaskChangeType changeType,
            String beforeValue,
            String afterValue
    ) {
        this.task = task;
        this.changedByUser = changedByUser;
        this.changeType = changeType;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
    }

    // Static factory method (Builder 대신 사용 가능)
    public static TaskHistory of(
            Task task,
            User changedByUser,
            TaskChangeType changeType,
            String beforeValue,
            String afterValue
    ) {
        return TaskHistory.builder()
                .task(task)
                .changedByUser(changedByUser)
                .changeType(changeType)
                .beforeValue(beforeValue)
                .afterValue(afterValue)
                .build();
    }
}