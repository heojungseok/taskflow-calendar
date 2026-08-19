package com.taskflow.calendar.worker;

import com.taskflow.calendar.domain.oauth.GoogleOAuthService;
import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.CalendarOutboxRepository;
import com.taskflow.calendar.domain.oauth.OAuthGoogleTokenRepository;
import com.taskflow.calendar.domain.outbox.CalendarOutboxService;
import com.taskflow.calendar.domain.outbox.OutboxPolicy;
import com.taskflow.calendar.integration.googlecalendar.GoogleCalendarService;
import com.taskflow.calendar.integration.googlecalendar.exception.NonRetryableIntegrationException;
import com.taskflow.calendar.integration.googlecalendar.exception.RetryableIntegrationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Calendar Outbox Worker
 * - {@code outbox.worker.fixed-delay}(기본 60초)마다 처리 가능한 Outbox 조회
 * - 조건부 UPDATE로 원자적 선점 (Race Condition 방지)
 * - Lease timeout: 5분
 * - Max retry: 6회
 *
 * <p>주기를 재시도 백오프의 최소 간격(1분)보다 길게 잡으면
 * {@code CalendarOutboxService#calculateNextRetry}의 앞 단계가 뭉개진다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CalendarOutboxWorker {

    private final CalendarOutboxRepository outboxRepository;
    private final CalendarOutboxService outboxService;
    private final GoogleCalendarService googleCalendarService;
    private final GoogleOAuthService googleOAuthService;
    private final OAuthGoogleTokenRepository tokenRepository;

    @Value("${outbox.worker.enabled:false}")
    private boolean schedulingEnabled;

    /** 유일한 실행 진입점은 scheduler다. */
    @Scheduled(fixedDelayString = "${outbox.worker.fixed-delay:60000}")
    public void scheduledPoll() {
        if (!schedulingEnabled) {
            return;
        }
        pollAndProcess();
    }

    void pollAndProcess() {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime leaseTimeout = now.minusMinutes(OutboxPolicy.LEASE_TIMEOUT_MINUTES.value());

            log.debug("[Worker] Polling at {}, leaseTimeout={}", now, leaseTimeout);

            // 1. 처리 가능한 Outbox 조회
            List<CalendarOutbox> processableOutboxes = outboxRepository.findProcessable(
                    now,
                    leaseTimeout,
                    OutboxPolicy.MAX_RETRY.value()
            );

            if (processableOutboxes.isEmpty()) {
                log.debug("[Worker] No processable outboxes found");
                return;
            }

            log.info("[Worker] Found {} processable outboxes", processableOutboxes.size());

            // 2. 각 Outbox 처리
            for (CalendarOutbox outbox : processableOutboxes) {
                try {
                    // Lock 시도 (조건부 UPDATE로 원자적 선점)
                    boolean claimed = outboxService.claimProcessing(outbox.getId(), leaseTimeout);

                    if (!claimed) {
                        log.debug("[Worker] Outbox {} already claimed by another worker", outbox.getId());
                        continue;
                    }

                    log.info("[Worker] Processing Outbox {} - OpType: {}, TaskId: {}, RetryCount: {}",
                            outbox.getId(), outbox.getOpType(), outbox.getTaskId(), outbox.getRetryCount());

                    processOne(outbox);

                } catch (Exception e) {
                    log.error("[Worker] Unexpected error processing Outbox {}", outbox.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("[Worker] Fatal error in polling cycle: {}", e.getMessage(), e);
        }
    }

    private void processOne(CalendarOutbox outbox) {
        try {
            // 0. 구글 연동이 없는 사용자면 호출 자체를 하지 않는다.
            //    데모 사용자는 영원히 연동이 없으므로 FAILED로 쌓이면 알람이 무의미해진다.
            if (skipIfNotLinked(outbox)) {
                return;
            }

            // 1. Google Calendar API 호출 (내부에서 Task 최신 상태 조회)
            googleCalendarService.handle(outbox);

            // 2. 성공 처리
            outboxService.markSuccess(outbox.getId());
            log.info("[Worker] Successfully processed Outbox {}", outbox.getId());

        } catch (RetryableIntegrationException e) {
            // 재시도 가능한 예외 (네트워크, 5xx 등)
            log.warn("[Worker] Retryable error on Outbox {}: {}",
                    outbox.getId(), e.getMessage());
            outboxService.markForRetry(outbox.getId(), e.getMessage());

        } catch (NonRetryableIntegrationException e) {
            if (e.getStatusCode() == 401) {
                handleTokenRefreshAndRetry(outbox, e);
            } else {
                log.error("[Worker] NonRetryable error on Outbox {}: {}",
                        outbox.getId(), e.getMessage());
                outboxService.markFailed(outbox.getId(), e.getMessage());
            }
        } catch (Exception e) {
            // 예상치 못한 예외 → Retryable로 처리
            log.error("[Worker] Unexpected error on Outbox {}: {}",
                    outbox.getId(), e.getMessage(), e);
            outboxService.markForRetry(outbox.getId(),
                    "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * 401 발생 시 토큰 갱신 후 재시도 대상으로 남김
     * - 갱신 성공 → markForRetry (다음 polling에서 재시도)
     * - 갱신 실패 → markFailed (수동 확인 필요)
     */
    /**
     * 구글 연동이 없으면 SKIPPED로 종결하고 true를 반환한다.
     *
     * <p>FAILED와 나누는 이유는 관측이다. "조치가 필요한 실패"와 "정상적으로 하지 않는 것"이
     * 한 상태로 뭉치면 대시보드를 봐도 대응 여부를 판단할 수 없다.
     * 지표 이름은 로그 키와 맞춘다: outbox_skipped{reason="no_google_link"}
     */
    private boolean skipIfNotLinked(CalendarOutbox outbox) {
        Long userId;
        try {
            userId = outboxService.extractUserIdFromPayload(outbox);
        } catch (RuntimeException e) {
            // payload에서 userId를 못 읽으면 연동 여부를 판단할 수 없다. 기존 경로로 보낸다.
            log.warn("[Worker] outbox_skip_check_failed outboxId={} reason={}",
                    outbox.getId(), e.getMessage());
            return false;
        }

        if (tokenRepository.existsByUserId(userId)) {
            return false;
        }

        log.info("[Worker] outbox_skipped outboxId={} taskId={} userId={} opType={} reason=no_google_link",
                outbox.getId(), outbox.getTaskId(), userId, outbox.getOpType());
        outboxService.markSkipped(outbox.getId(), "구글 연동 없음. userId=" + userId);
        return true;
    }

    private void handleTokenRefreshAndRetry(CalendarOutbox outbox, NonRetryableIntegrationException e) {
        try {
            Long userId = outboxService.extractUserIdFromPayload(outbox);
            log.warn("[Worker] 401 detected on Outbox {}. Attempting token refresh for userId={}",
                    outbox.getId(), userId);

            googleOAuthService.refreshAccessToken(userId);

            // 갱신 성공 → 재시도 대상으로 남김
            outboxService.markForRetry(outbox.getId(), "Token refreshed, will retry");
            log.info("[Worker] Token refreshed successfully. Outbox {} will be retried", outbox.getId());

        } catch (Exception refreshException) {
            // 갱신 실패 → FAILED
            log.error("[Worker] Token refresh failed for Outbox {}: {}",
                    outbox.getId(), refreshException.getMessage());
            outboxService.markFailed(outbox.getId(),
                    "Token refresh failed: " + refreshException.getMessage());
        }
    }
}
