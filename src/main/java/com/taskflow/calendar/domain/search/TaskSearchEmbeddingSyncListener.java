package com.taskflow.calendar.domain.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class TaskSearchEmbeddingSyncListener {

    private final TaskSearchEmbeddingService taskSearchEmbeddingService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDocumentChanged(TaskSearchDocumentChangedEvent event) {
        taskSearchEmbeddingService.refreshTask(event.getTaskId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDocumentDeleted(TaskSearchDocumentDeletedEvent event) {
        taskSearchEmbeddingService.deleteTask(event.getTaskId());
    }
}
