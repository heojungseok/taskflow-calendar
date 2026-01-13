package com.taskflow.calendar.domain.task;

import com.taskflow.calendar.domain.task.exception.TaskStatusTransitionNotAllowedException;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Task 상태 전이 정책 (Utility Class)
 * - 스펙의 상태 전이 매트릭스를 구현
 * - 인스턴스 생성 불가, static 메서드만 제공
 */
public final class TaskStatusPolicy { // final 상속 방지

    // 상태 전이 규칙
    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED_TRANSITIONS;

    // private 생성자: 인스턴스 생성 방지
    private TaskStatusPolicy() {
        throw new AssertionError("Cannot instantiate utility class");
    }

    // static 초기화 블록: 클래스 로딩 시 한 번만 실행
    static {
        ALLOWED_TRANSITIONS = new HashMap<>();

        // REQUESTED -> IN_PROGRESS, BLOCKED
        ALLOWED_TRANSITIONS.put(TaskStatus.REQUESTED,
                Set.of(TaskStatus.IN_PROGRESS, TaskStatus.BLOCKED));

        // IN_PROGRESS -> DONE, BLOCKED
        ALLOWED_TRANSITIONS.put(TaskStatus.IN_PROGRESS,
                Set.of(TaskStatus.DONE, TaskStatus.BLOCKED));

        // BLOCKED -> IN_PROGRESS
        ALLOWED_TRANSITIONS.put(TaskStatus.BLOCKED,
                Set.of(TaskStatus.IN_PROGRESS));

        // DONE -> (없음)
        ALLOWED_TRANSITIONS.put(TaskStatus.DONE,
                Set.of());
    }

    /**
     * 상태 전이 가능 여부 확인
     *
     * @param from 현재 상태
     * @param to 전이할 상태
     * @return 전이 가능하면 true, 불가능하면 false
     */
    public static boolean canTransition(TaskStatus from, TaskStatus to) {
        if (from == null || to == null) {
            return false;
        }

        Set<TaskStatus> allowedStatuses = ALLOWED_TRANSITIONS.get(from);
        return allowedStatuses != null && allowedStatuses.contains(to);
    }

    /**
     * 상태 전이 검증 (불가능하면 예외 발생)
     *
     * @param from 현재 상태
     * @param to 전이할 상태
     * @throws TaskStatusTransitionNotAllowedException 전이 불가능한 경우
     */
    public static void validateTransition(TaskStatus from, TaskStatus to) {
        if (!canTransition(from, to)) {
            throw new TaskStatusTransitionNotAllowedException(from, to);
        }
    }
}