package com.taskflow.planning.task;

/**
 * Task 변경 도메인 이벤트. planning은 누가 듣는지 모른다.
 *
 * <p>calendarsync는 같은 트랜잭션 안에서(@EventListener) Outbox를 적재하고, assistant는 커밋 후 검색 색인을 갱신한다. 동기 리스너의 예외는
 * 발행한 트랜잭션을 롤백시킨다.
 */
public record TaskChangedEvent(Task task, Type type) {

    public enum Type {
        CREATED,
        UPDATED,
        STATUS_CHANGED,
        DELETED
    }

    public Long taskId() {
        return task.getId();
    }
}
