package com.taskflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContainerStatsCollectorScriptTest {

    @Test
    @DisplayName("컨테이너 자원 수집 스크립트의 셸 테스트가 CI 셸에서도 통과한다")
    void collectorShellTestPasses() throws Exception {
        Process process = new ProcessBuilder("sh", "deploy/container-stats/collect-test.sh")
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(finished).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("PASS: container stats collection");
    }
}
