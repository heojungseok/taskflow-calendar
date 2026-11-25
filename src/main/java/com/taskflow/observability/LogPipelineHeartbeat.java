package com.taskflow.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LogPipelineHeartbeat {

    public static final String MESSAGE = "log pipeline heartbeat";

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void beat() {
        log.info(MESSAGE);
    }
}
