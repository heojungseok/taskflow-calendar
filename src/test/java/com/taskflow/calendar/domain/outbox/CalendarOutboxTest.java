package com.taskflow.calendar.domain.outbox;

import com.taskflow.calendar.domain.outbox.exception.InvalidOutboxStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CalendarOutbox Entity 테스트")
class CalendarOutboxTest {

    private CalendarOutbox outbox;
    private static final Long TASK_ID = 1L;
    private static final String VALID_PAYLOAD = "{\"taskId\": 1, \"opType\": \"UPSERT\"}";

    @BeforeEach
    void setUp() {
        outbox = CalendarOutbox.forUpsert(TASK_ID, VALID_PAYLOAD);
    }

    // =========================================================
    // 1. Factory Methods 테스트
    // =========================================================
    @Nested
    @DisplayName("Static Factory Methods")
    class FactoryMethodsTest {

        @Test
        @DisplayName("forUpsert: UPSERT Outbox 생성 확인")
        void forUpsert_생성_확인() {
            // when
            CalendarOutbox upsert = CalendarOutbox.forUpsert(TASK_ID, VALID_PAYLOAD);

            // then
            assertThat(upsert.getTaskId()).isEqualTo(TASK_ID);
            assertThat(upsert.getOpType()).isEqualTo(OutboxOpType.UPSERT);
            assertThat(upsert.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(upsert.getRetryCount()).isZero();
            assertThat(upsert.getPayload()).isEqualTo(VALID_PAYLOAD);
        }

        @Test
        @DisplayName("forDelete: DELETE Outbox 생성 확인")
        void forDelete_생성_확인() {
            // when
            CalendarOutbox delete = CalendarOutbox.forDelete(TASK_ID, VALID_PAYLOAD);

            // then
            assertThat(delete.getTaskId()).isEqualTo(TASK_ID);
            assertThat(delete.getOpType()).isEqualTo(OutboxOpType.DELETE);
            assertThat(delete.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(delete.getRetryCount()).isZero();
        }
    }

    // =========================================================
    // 2. 상태 전이 테스트
    // =========================================================
    @Nested
    @DisplayName("상태 전이 검증")
    class StateTransitionTest {

        @Test
        @DisplayName("PENDING → PROCESSING 전이 가능")
        void PENDING_to_PROCESSING() {
            // given
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);

            // when
            outbox.markAsProcessing();

            // then
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        }

        @Test
        @DisplayName("PROCESSING → SUCCESS 전이 가능")
        void PROCESSING_to_SUCCESS() {
            // given
            outbox.markAsProcessing();

            // when
            outbox.markAsSuccess();

            // then
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.SUCCESS);
            assertThat(outbox.getLastError()).isNull();
            assertThat(outbox.getNextRetryAt()).isNull();
        }

        @Test
        @DisplayName("PROCESSING → FAILED (재시도) 전이 가능")
        void PROCESSING_to_FAILED_재시도() {
            // given
            outbox.markAsProcessing();
            LocalDateTime nextRetry = LocalDateTime.now().plusMinutes(5);

            // when
            outbox.markForRetry("Temporary error", nextRetry);

            // then
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(outbox.getRetryCount()).isEqualTo(1);
            assertThat(outbox.getLastError()).isEqualTo("Temporary error");
            assertThat(outbox.getNextRetryAt()).isEqualTo(nextRetry);
        }

        @Test
        @DisplayName("PROCESSING → FAILED (최종 실패) 전이 가능")
        void PROCESSING_to_FAILED_최종() {
            // given
            outbox.markAsProcessing();

            // when
            outbox.markAsFailed("Permanent error");

            // then
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(outbox.getLastError()).isEqualTo("Permanent error");
            assertThat(outbox.getNextRetryAt()).isNull();
        }
    }

    // =========================================================
    // 3. 잘못된 상태 전이 테스트
    // =========================================================
    @Nested
    @DisplayName("잘못된 상태 전이 검증")
    class InvalidStateTransitionTest {

        @Test
        @DisplayName("PENDING → SUCCESS 직접 전이 불가")
        void PENDING_to_SUCCESS_불가() {
            // given
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);

            // when & then
            assertThatThrownBy(() -> outbox.markAsSuccess())
                    .isInstanceOf(InvalidOutboxStateTransitionException.class)
                    .hasMessageContaining("Cannot transition from PENDING");
        }

        @Test
        @DisplayName("PENDING → FAILED 직접 전이 불가")
        void PENDING_to_FAILED_불가() {
            // given
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);

            // when & then
            assertThatThrownBy(() -> outbox.markForRetry("Error", LocalDateTime.now()))
                    .isInstanceOf(InvalidOutboxStateTransitionException.class)
                    .hasMessageContaining("Cannot transition from PENDING");
        }

        @Test
        @DisplayName("SUCCESS → PROCESSING 전이 불가")
        void SUCCESS_to_PROCESSING_불가() {
            // given
            outbox.markAsProcessing();
            outbox.markAsSuccess();
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.SUCCESS);

            // when & then
            assertThatThrownBy(() -> outbox.markForRetry("Error", LocalDateTime.now()))
                    .isInstanceOf(InvalidOutboxStateTransitionException.class)
                    .hasMessageContaining("Cannot transition from SUCCESS");
        }

        @Test
        @DisplayName("FAILED → SUCCESS 직접 전이 불가 (PROCESSING 거쳐야 함)")
        void FAILED_to_SUCCESS_불가() {
            // given
            outbox.markAsProcessing();
            outbox.markForRetry("Error", LocalDateTime.now().plusMinutes(1));
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);

            // when & then
            assertThatThrownBy(() -> outbox.markAsSuccess())
                    .isInstanceOf(InvalidOutboxStateTransitionException.class)
                    .hasMessageContaining("Cannot transition from FAILED");
        }
    }

    // =========================================================
    // 4. FAILED → PROCESSING (재시도) 시나리오
    // =========================================================
    @Nested
    @DisplayName("FAILED 재시도 시나리오")
    class FailedRetryScenarioTest {

        @Test
        @DisplayName("FAILED → PROCESSING 전이 가능 (재시도)")
        void FAILED_to_PROCESSING_재시도() {
            // given: FAILED 상태
            outbox.markAsProcessing();
            outbox.markForRetry("Temporary error", LocalDateTime.now().plusMinutes(1));
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(outbox.getRetryCount()).isEqualTo(1);

            // when: 재시도 (Worker가 다시 선점)
            outbox.markAsProcessing();

            // then
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
            assertThat(outbox.getRetryCount()).isEqualTo(1); // 유지됨
        }

        @Test
        @DisplayName("재시도 후 성공 시나리오")
        void 재시도_후_성공() {
            // given: FAILED 상태
            outbox.markAsProcessing();
            outbox.markForRetry("Temporary error", LocalDateTime.now().plusMinutes(1));

            // when: 재시도 후 성공
            outbox.markAsProcessing();
            outbox.markAsSuccess();

            // then
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.SUCCESS);
            assertThat(outbox.getRetryCount()).isEqualTo(1);
            assertThat(outbox.getLastError()).isNull();
        }

        @Test
        @DisplayName("재시도 횟수 증가 확인")
        void 재시도_횟수_증가() {
            // given
            outbox.markAsProcessing();

            // when: 3회 실패
            for (int i = 0; i < 3; i++) {
                outbox.markForRetry("Error " + (i + 1), LocalDateTime.now().plusMinutes(i + 1));
                if (i < 2) { // 마지막은 PROCESSING으로 전이하지 않음
                    outbox.markAsProcessing();
                }
            }

            // then
            assertThat(outbox.getRetryCount()).isEqualTo(3);
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
        }
    }

    // =========================================================
    // 5. 엣지 케이스 테스트
    // =========================================================
    @Nested
    @DisplayName("엣지 케이스")
    class EdgeCaseTest {

        @Test
        @DisplayName("markForRetry: nextRetryAt null 허용")
        void markForRetry_nextRetryAt_null() {
            // given
            outbox.markAsProcessing();

            // when
            outbox.markForRetry("Error", null);

            // then
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(outbox.getNextRetryAt()).isNull(); // null 허용
        }

        @Test
        @DisplayName("markAsFailed: lastError null 허용 안 함")
        void markAsFailed_lastError_not_null() {
            // given
            outbox.markAsProcessing();

            // when
            outbox.markAsFailed(null);

            // then
            assertThat(outbox.getLastError()).isNull(); // null 허용 (비즈니스 정책에 따라)
        }
    }
}
