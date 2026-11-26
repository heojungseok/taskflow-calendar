package com.taskflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LogPipelineHeartbeatTest {

    @Test
    @DisplayName("Grafana 수집 끊김 알림이 찾는 문구를 INFO로 남긴다")
    void logsHeartbeatLineThatAlertSearchesFor(CapturedOutput output) {
        new LogPipelineHeartbeat().beat();

        assertThat(output).contains("INFO").contains("log pipeline heartbeat");
    }
}
