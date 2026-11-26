package com.taskflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class IncidentKnowledgeSignatureTest {

    private static final Path KNOWLEDGE = Path.of("incident-agent/knowledge");
    private static final Pattern SIGNATURE = Pattern.compile("^- 식별 패턴: `ERROR (\\S+) : (.*)`$", Pattern.MULTILINE);
    private static final Pattern LOGGER = Pattern.compile("^- 로거: `(\\S+)`$", Pattern.MULTILINE);

    static List<Path> documents() throws IOException {
        try (Stream<Path> files = Files.list(KNOWLEDGE)) {
            return files.filter(path -> path.toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
    }

    @ParameterizedTest
    @MethodSource("documents")
    @DisplayName("장애 문서의 식별 패턴은 실제 클래스가 logback으로 찍는 로거 이름과 소스의 로그 문구로 시작한다")
    void signatureMatchesTheRealLoggerAndLogStatement(Path document) throws Exception {
        String text = Files.readString(document);
        Matcher signature = SIGNATURE.matcher(text);
        Matcher logger = LOGGER.matcher(text);
        assertThat(signature.find()).as("식별 패턴").isTrue();
        assertThat(logger.find()).as("로거").isTrue();
        String className = logger.group(1);

        Class.forName(className);
        String printed = syslogLayout().doLayout(errorEvent(className));
        String source = Files.readString(Path.of("src/main/java", className.replace('.', '/') + ".java"));

        assertThat(printed).startsWith("backend: ERROR " + signature.group(1) + " : ");
        assertThat(source).contains(signature.group(2).replace("#", "{}"));
    }

    private PatternLayout syslogLayout() throws IOException {
        String logback = Files.readString(Path.of("src/main/resources/logback-spring.xml"));
        Matcher matcher = Pattern.compile("<suffixPattern>(.*)</suffixPattern>").matcher(logback);
        assertThat(matcher.find()).isTrue();
        PatternLayout layout = new PatternLayout();
        layout.setContext(new LoggerContext());
        layout.setPattern(matcher.group(1).replace("&lt;", "<").replace("&gt;", ">"));
        layout.start();
        return layout;
    }

    private LoggingEvent errorEvent(String loggerName) {
        LoggerContext context = new LoggerContext();
        LoggingEvent event = new LoggingEvent(
                LoggingEvent.class.getName(), context.getLogger(loggerName), Level.ERROR, "message", null, null);
        event.setMDCPropertyMap(Map.of());
        return event;
    }
}
