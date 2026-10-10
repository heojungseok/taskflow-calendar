package com.taskflow.assistant.search;

import com.taskflow.planning.task.TaskChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class TaskSearchEmbeddingSyncListener {

    private final TaskSearchEmbeddingService taskSearchEmbeddingService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(TaskChangedEvent event) {
        if (event.type() == TaskChangedEvent.Type.DELETED) {
            taskSearchEmbeddingService.deleteTask(event.taskId());
        } else {
            taskSearchEmbeddingService.refreshTask(event.taskId());
        }
    }
}
