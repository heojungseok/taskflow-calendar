package com.taskflow.web;

import com.taskflow.calendar.domain.outbox.CalendarOutboxService;
import com.taskflow.calendar.domain.outbox.OutboxStatus;
import com.taskflow.calendar.worker.CalendarOutboxWorker;
import com.taskflow.config.SecurityConfig;
import com.taskflow.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/admin/** 는 permitAll 이었다. 무인증 GET 하나가 워커를 돌려 실제 구글 API를 호출했다.
 * 이 테스트가 지키는 것: ① 인증 없이는 못 들어온다 ② 조회 범위는 JWT의 userId 다
 * ③ 워커 트리거는 GET 으로 실행되지 않는다.
 */
@WebMvcTest(OutboxController.class)
@Import(SecurityConfig.class)
class OutboxControllerSecurityTest {

    private static final String BASE = "/api/admin/calendar-outbox";
    private static final String TOKEN = "valid-token";
    private static final Long USER_ID = 7L;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CalendarOutboxService outboxService;

    @MockitoBean
    CalendarOutboxWorker worker;

    @MockitoBean
    JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void stubToken() {
        given(jwtTokenProvider.validateToken(TOKEN)).willReturn(true);
        given(jwtTokenProvider.getUserIdFromToken(TOKEN)).willReturn(USER_ID);
    }

    @Nested
    @DisplayName("인증")
    class Authentication {

        @Test
        @DisplayName("토큰 없이 목록 조회하면 401")
        void listWithoutToken() throws Exception {
            mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
            verify(outboxService, never()).listOutboxes(any(), any(), any());
        }

        @Test
        @DisplayName("토큰 없이 워커를 트리거하면 401이고 워커는 돌지 않는다")
        void triggerWithoutToken() throws Exception {
            mvc.perform(post(BASE + "/trigger-worker")).andExpect(status().isUnauthorized());
            verify(worker, never()).pollAndProcess();
        }

        @Test
        @DisplayName("유효한 토큰이면 통과한다")
        void listWithToken() throws Exception {
            given(outboxService.listOutboxes(any(), any(), any())).willReturn(List.of());

            mvc.perform(get(BASE).header("Authorization", "Bearer " + TOKEN))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("조회 범위")
    class Scoping {

        @Test
        @DisplayName("목록 조회는 요청 파라미터가 아니라 JWT의 userId로 스코프된다")
        void listIsScopedToTokenUser() throws Exception {
            given(outboxService.listOutboxes(any(), any(), any())).willReturn(List.of());

            mvc.perform(get(BASE)
                            .param("status", "PENDING")
                            .header("Authorization", "Bearer " + TOKEN))
                    .andExpect(status().isOk());

            verify(outboxService).listOutboxes(eq(USER_ID), eq(OutboxStatus.PENDING), eq(null));
        }

        @Test
        @DisplayName("단건 조회도 JWT의 userId를 함께 넘긴다")
        void getIsScopedToTokenUser() throws Exception {
            mvc.perform(get(BASE + "/1").header("Authorization", "Bearer " + TOKEN));

            verify(outboxService).getOutbox(eq(1L), eq(USER_ID));
        }
    }

    @Nested
    @DisplayName("워커 트리거")
    class TriggerWorker {

        @Test
        @DisplayName("GET으로는 실행되지 않는다 - 크롤러·프리페치 방어")
        void getIsNotAllowed() throws Exception {
            mvc.perform(get(BASE + "/trigger-worker").header("Authorization", "Bearer " + TOKEN))
                    .andExpect(status().isMethodNotAllowed());
            verify(worker, never()).pollAndProcess();
        }

        @Test
        @DisplayName("인증된 POST면 실행된다")
        void postRunsWorker() throws Exception {
            mvc.perform(post(BASE + "/trigger-worker").header("Authorization", "Bearer " + TOKEN))
                    .andExpect(status().isOk());
            verify(worker).pollAndProcess();
        }
    }
}
