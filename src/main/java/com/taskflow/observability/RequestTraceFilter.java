package com.taskflow.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    private static final String TRACE_ID = "traceId";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
        MDC.put(TRACE_ID, traceId);
        response.setHeader(REQUEST_ID_HEADER, traceId);
        long start = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            logRequest(request, completed ? response.getStatus() : 500, start);
            MDC.remove(TRACE_ID);
        }
    }

    private void logRequest(HttpServletRequest request, int status, long startNanos) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/actuator")) {
            return;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("{} {} {} {}ms", request.getMethod(), uri, status, elapsedMs);
    }
}
