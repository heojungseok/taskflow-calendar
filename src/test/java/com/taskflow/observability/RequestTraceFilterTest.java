package com.taskflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestTraceFilterTest {

    private final RequestTraceFilter filter = new RequestTraceFilter();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestTraceFilter.class);

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("요청마다 traceId를 MDC와 응답 헤더에 넣고 끝나면 MDC를 비운다")
    void putsTraceIdInMdcAndResponseHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/tasks"),
                response,
                (req, res) -> seenInChain.set(MDC.get("traceId")));

        assertThat(seenInChain.get()).matches("[0-9a-f]{16}");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(seenInChain.get());
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("요청 한 줄을 query string 없이 상태·시간과 함께 남긴다")
    void logsOneLinePerRequestWithoutQueryString() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/oauth/google/callback");
        request.setQueryString("code=secret&state=abc");

        filter.doFilter(
                request, new MockHttpServletResponse(), (req, res) -> ((HttpServletResponse) res).setStatus(302));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).matches("GET /api/oauth/google/callback 302 \\d+ms");
            assertThat(event.getMDCPropertyMap()).containsKey("traceId");
        });
    }

    @Test
    @DisplayName("actuator 요청은 로그를 남기지 않는다")
    void skipsActuatorRequests() throws Exception {
        filter.doFilter(
                new MockHttpServletRequest("GET", "/actuator/health/readiness"),
                new MockHttpServletResponse(),
                (req, res) -> {});

        assertThat(appender.list).isEmpty();
    }
}
