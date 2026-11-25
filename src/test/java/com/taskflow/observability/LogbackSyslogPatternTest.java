package com.taskflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogbackSyslogPatternTest {

    private static final Pattern HEARTBEAT_ALERT =
            Pattern.compile("^INFO +\\S*LogPipelineHeartbeat : log pipeline heartbeat$");

    private final LoggerContext context = new LoggerContext();
    private PatternLayout layout;

    @BeforeEach
    void setUp() throws Exception {
        String logback = Files.readString(Path.of("src/main/resources/logback-spring.xml"));
        Matcher matcher = Pattern.compile("<suffixPattern>(.*)</suffixPattern>").matcher(logback);
        assertThat(matcher.find()).isTrue();
        layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(matcher.group(1).replace("&lt;", "<").replace("&gt;", ">"));
        layout.start();
    }

    @Test
    @DisplayName("heartbeat 줄은 로그 끊김 알림 정규식에 그대로 맞는다")
    void heartbeatLineStillMatchesSilentAlert() {
        String line =
                format(Level.INFO, LogPipelineHeartbeat.class.getName(), LogPipelineHeartbeat.MESSAGE, null, Map.of());

        assertThat(line).startsWith("backend: ");
        assertThat(HEARTBEAT_ALERT.matcher(line.substring("backend: ".length())).matches())
                .isTrue();
    }

    @Test
    @DisplayName("요청 안의 로그는 줄 끝에 traceId를 붙인다")
    void appendsTraceIdFromMdc() {
        String line = format(
                Level.WARN, "com.taskflow.Some", "Validation error", null, Map.of("traceId", "4f2a9c01d3e5b6a7"));

        assertThat(line).endsWith(" : Validation error traceId=4f2a9c01d3e5b6a7");
    }

    @Test
    @DisplayName("예외는 원인까지 한 줄로 이어 붙이고 길이를 제한한다")
    void appendsSingleLineStackTrace() {
        IllegalStateException error = new IllegalStateException("outer", new IllegalArgumentException("root cause"));

        String line = format(Level.ERROR, "com.taskflow.Some", "Unexpected error", error, Map.of());

        assertThat(line).startsWith("backend: ERROR ").doesNotContain("\n", "\r");
        assertThat(line)
                .contains("Unexpected error | java.lang.IllegalArgumentException: root cause")
                .contains("Wrapped by: java.lang.IllegalStateException: outer");
        assertThat(line.split(" \\| ")).hasSizeLessThanOrEqualTo(30);
    }

    @Test
    @DisplayName("원인이 깊고 한글 메시지가 길어도 Alloy syslog 한도(8192바이트) 안에 원인을 먼저 담는다")
    void keepsRootCauseWithinAlloySyslogLimit() {
        Throwable error = new IllegalArgumentException("근본 원인 " + "가".repeat(300));
        for (int depth = 0; depth < 6; depth++) {
            error = new IllegalStateException("감싼 예외 " + depth + " " + "나".repeat(300), error);
        }

        String line = format(Level.ERROR, "com.taskflow.Some", "Unexpected error", error, Map.of());

        assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThan(8192);
        assertThat(line).contains("Unexpected error | java.lang.IllegalArgumentException: 근본 원인");
    }

    @Test
    @DisplayName("DB 오류 Detail(이메일·Task 본문이 담길 수 있음)을 예외 메시지에 싣지 않는다")
    void postgresErrorDetailIsNotLogged() throws Exception {
        String application = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(application).contains("logServerErrorDetail: false");
    }

    private String format(Level level, String logger, String message, Throwable error, Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent(logger, context.getLogger(logger), level, message, error, null);
        event.setMDCPropertyMap(mdc);
        return layout.doLayout(event);
    }
}
