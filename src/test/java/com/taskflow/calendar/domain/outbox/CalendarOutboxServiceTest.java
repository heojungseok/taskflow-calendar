package com.taskflow.calendar.domain.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.calendar.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CalendarOutboxService 테스트")
class CalendarOutboxServiceTest {

    @Mock
    private CalendarOutboxRepository outboxRepository;

    @InjectMocks
    private CalendarOutboxService outboxService;

    private ObjectMapper objectMapper = new ObjectMapper();

    private Task task;
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() throws Exception {
        // ObjectMapper 주입 (Mock 대신 실제 인스턴스)
        var field = CalendarOutboxService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(outboxService, objectMapper);

        // Task 생성 (Reflection 사용)
        task = createTask(1L, "Test Task", TaskStatus.REQUESTED,
                LocalDateTime.now().plusDays(1), true, "event-123");

        // SecurityContext 설정 (MockedStatic 대신 사용)
        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(USER_ID, null, null);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // =========================================================
    // 1. Coalescing 테스트 - enqueueUpsert
    // =========================================================
    @Nested
    @DisplayName("enqueueUpsert: Coalescing 규칙 검증")
    class EnqueueUpsertCoalescingTest {

        @Test
        @DisplayName("Rule A-1: PENDING DELETE 제거 확인")
        void ruleA1_PENDING_DELETE_제거() {
            // given
            when(outboxRepository.deleteByTaskIdAndStatusAndOpType(any(), any(), any())).thenReturn(0);
            when(outboxRepository.save(any())).thenReturn(null);

            // when
            outboxService.enqueueUpsert(task);

            // then
            verify(outboxRepository).deleteByTaskIdAndStatusAndOpType(
                    task.getId(), OutboxStatus.PENDING, OutboxOpType.DELETE);
        }

        @Test
        @DisplayName("Rule A-2: PENDING UPSERT 제거 확인")
        void ruleA2_PENDING_UPSERT_제거() {
            // given
            when(outboxRepository.deleteByTaskIdAndStatusAndOpType(any(), any(), any())).thenReturn(0);
            when(outboxRepository.save(any())).thenReturn(null);

            // when
            outboxService.enqueueUpsert(task);

            // then
            verify(outboxRepository).deleteByTaskIdAndStatusAndOpType(
                    task.getId(), OutboxStatus.PENDING, OutboxOpType.UPSERT);
        }

        @Test
        @DisplayName("새 UPSERT Outbox 저장 확인")
        void 새_UPSERT_저장() {
            // given
            when(outboxRepository.deleteByTaskIdAndStatusAndOpType(any(), any(), any())).thenReturn(0);
            when(outboxRepository.save(any())).thenReturn(null);

            // when
            outboxService.enqueueUpsert(task);

            // then
            verify(outboxRepository).save(argThat(outbox ->
                    outbox.getTaskId().equals(task.getId()) &&
                    outbox.getOpType() == OutboxOpType.UPSERT &&
                    outbox.getStatus() == OutboxStatus.PENDING
            ));
        }
    }

    // =========================================================
    // 2. Coalescing 테스트 - enqueueDelete
    // =========================================================
    @Nested
    @DisplayName("enqueueDelete: Coalescing 규칙 검증")
    class EnqueueDeleteCoalescingTest {

        @Test
        @DisplayName("Rule B-1: PENDING UPSERT 제거 확인")
        void ruleB1_PENDING_UPSERT_제거() {
            // given
            when(outboxRepository.deleteByTaskIdAndStatusAndOpType(any(), any(), any())).thenReturn(0);
            when(outboxRepository.existsByTaskIdAndStatusAndOpType(
                    task.getId(), OutboxStatus.PENDING, OutboxOpType.DELETE))
                    .thenReturn(false);
            when(outboxRepository.save(any())).thenReturn(null);

            // when
            outboxService.enqueueDelete(task);

            // then
            verify(outboxRepository).deleteByTaskIdAndStatusAndOpType(
                    task.getId(), OutboxStatus.PENDING, OutboxOpType.UPSERT);
        }

        @Test
        @DisplayName("Rule B-2: PENDING DELETE 중복 시 skip")
        void ruleB2_PENDING_DELETE_중복_skip() {
            // given
            when(outboxRepository.deleteByTaskIdAndStatusAndOpType(any(), any(), any())).thenReturn(0);
            when(outboxRepository.existsByTaskIdAndStatusAndOpType(
                    task.getId(), OutboxStatus.PENDING, OutboxOpType.DELETE))
                    .thenReturn(true); // 이미 존재

            // when
            outboxService.enqueueDelete(task);

            // then
            verify(outboxRepository, never()).save(any());
        }

        @Test
        @DisplayName("새 DELETE Outbox 저장 확인")
        void 새_DELETE_저장() {
            // given
            when(outboxRepository.deleteByTaskIdAndStatusAndOpType(any(), any(), any())).thenReturn(0);
            when(outboxRepository.existsByTaskIdAndStatusAndOpType(
                    task.getId(), OutboxStatus.PENDING, OutboxOpType.DELETE))
                    .thenReturn(false);
            when(outboxRepository.save(any())).thenReturn(null);

            // when
            outboxService.enqueueDelete(task);

            // then
            verify(outboxRepository).save(argThat(outbox ->
                    outbox.getTaskId().equals(task.getId()) &&
                    outbox.getOpType() == OutboxOpType.DELETE &&
                    outbox.getStatus() == OutboxStatus.PENDING
            ));
        }
    }

    // =========================================================
    // 3. Payload 무결성 테스트
    // =========================================================
    @Nested
    @DisplayName("Payload 생성 검증")
    class PayloadIntegrityTest {

        @Test
        @DisplayName("buildUpsertPayload: 필수 필드 포함 확인")
        void upsertPayload_필수필드_확인() throws Exception {
            // given
            when(outboxRepository.deleteByTaskIdAndStatusAndOpType(any(), any(), any())).thenReturn(0);
            when(outboxRepository.save(any())).thenReturn(null);

            // when
            outboxService.enqueueUpsert(task);

            // then
            verify(outboxRepository).save(argThat(outbox -> {
                try {
                    Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), Map.class);
                    Map<String, Object> event = (Map<String, Object>) payload.get("event");
                    Map<String, Object> meta = (Map<String, Object>) payload.get("meta");

                    return payload.get("opType").equals("UPSERT") &&
                           event.get("eventId").equals("event-123") &&
                           event.get("title").equals("Test Task") &&
                           event.containsKey("startAt") &&
                           event.containsKey("endAt") &&
                           meta.get("requestedByUserId").equals(USER_ID.intValue());
                } catch (Exception e) {
                    return false;
                }
            }));
        }

        @Test
        @DisplayName("buildDeletePayload: eventId만 포함 확인")
        void deletePayload_eventId만_확인() throws Exception {
            // given
            when(outboxRepository.deleteByTaskIdAndStatusAndOpType(any(), any(), any())).thenReturn(0);
            when(outboxRepository.existsByTaskIdAndStatusAndOpType(
                    task.getId(), OutboxStatus.PENDING, OutboxOpType.DELETE))
                    .thenReturn(false);
            when(outboxRepository.save(any())).thenReturn(null);

            // when
            outboxService.enqueueDelete(task);

            // then
            verify(outboxRepository).save(argThat(outbox -> {
                try {
                    Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), Map.class);
                    Map<String, Object> event = (Map<String, Object>) payload.get("event");

                    return payload.get("opType").equals("DELETE") &&
                           event.get("eventId").equals("event-123") &&
                           event.size() == 1; // eventId만!
                } catch (Exception e) {
                    return false;
                }
            }));
        }

        @Test
        @DisplayName("TaskStatus.DONE 시 [DONE] prefix 포함")
        void DONE_상태_prefix_확인() throws Exception {
            // given
            when(outboxRepository.deleteByTaskIdAndStatusAndOpType(any(), any(), any())).thenReturn(0);
            when(outboxRepository.save(any())).thenReturn(null);

            Task doneTask = createTask(2L, "Completed Task", TaskStatus.DONE,
                    LocalDateTime.now().plusDays(1), true, "event-456");

            // when
            outboxService.enqueueUpsert(doneTask);

            // then
            verify(outboxRepository).save(argThat(outbox -> {
                try {
                    Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), Map.class);
                    Map<String, Object> event = (Map<String, Object>) payload.get("event");
                    String title = (String) event.get("title");

                    return title.startsWith("[DONE] ");
                } catch (Exception e) {
                    return false;
                }
            }));
        }

        @Test
        @DisplayName("extractUserIdFromPayload: userId 추출 성공")
        void userId_추출_성공() throws Exception {
            // given
            String payload = objectMapper.writeValueAsString(Map.of(
                    "meta", Map.of("requestedByUserId", USER_ID)
            ));
            CalendarOutbox outbox = CalendarOutbox.forUpsert(1L, payload);

            // when
            Long extractedUserId = outboxService.extractUserIdFromPayload(outbox);

            // then
            assertThat(extractedUserId).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("extractUserIdFromPayload: 잘못된 구조 시 예외")
        void userId_추출_실패() {
            // given
            CalendarOutbox outbox = CalendarOutbox.forUpsert(1L, "{\"invalid\": true}");

            // when & then
            assertThatThrownBy(() -> outboxService.extractUserIdFromPayload(outbox))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("userId 추출 실패");
        }
    }

    // =========================================================
    // Helper Methods
    // =========================================================
    private Task createTask(Long id, String title, TaskStatus status,
                           LocalDateTime dueAt, Boolean syncEnabled, String eventId) throws Exception {
        Project project = mock(Project.class);
        User user = mock(User.class);

        Task task = Task.createTask(project, title, "Description",
                user, dueAt.minusHours(1), dueAt, syncEnabled);

        // Reflection으로 private 필드 설정
        setField(task, "id", id);
        setField(task, "status", status);
        setField(task, "calendarEventId", eventId);

        return task;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
