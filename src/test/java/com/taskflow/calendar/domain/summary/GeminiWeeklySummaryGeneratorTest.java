package com.taskflow.calendar.domain.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.common.ErrorCode;
import com.taskflow.config.GeminiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class GeminiWeeklySummaryGeneratorTest {

    private GeminiWeeklySummaryGenerator generator;
    private ObjectMapper objectMapper;
    private Project project;

    @BeforeEach
    void setUp() {
        GeminiProperties properties = new GeminiProperties();
        properties.setApiKey("test-key");
        properties.setModel("gemini-2.5-flash");
        properties.setBaseUrl("https://example.com/v1beta");
        properties.setTimeoutSeconds(5);
        properties.setTemperature(0.2);

        objectMapper = new ObjectMapper().findAndRegisterModules();
        generator = new GeminiWeeklySummaryGenerator(properties, objectMapper);
        project = Project.of("TaskFlow");
    }

    @Test
    @DisplayName("compressDescription_중요키워드를보존하면서길이를줄인다")
    void compressDescription_keepsImportantKeywords() throws Exception {
        Task task = Task.createTask(
                project,
                "운영 배포 체크리스트 정리",
                "3월 27일 오전 배포 전에 체크해야 하는 항목을 문서로 정리해야 한다. "
                        + "환경 변수 확인, Google OAuth redirect URI 점검, Gemini API key 설정 확인, "
                        + "요약 생성 실패 시 대응 절차를 포함해야 한다. 아직 일정 시간을 확정하지 못했지만 이번 주 안에는 반드시 정리돼야 한다.",
                null,
                null,
                LocalDateTime.now().plusDays(1),
                false
        );

        String compressed = (String) invokePrivate(
                generator,
                "compressDescription",
                new Class[]{Task.class},
                task
        );

        assertTrue(compressed.length() <= 120);
        assertTrue(compressed.contains("Google OAuth") || compressed.contains("Gemini API key"));
    }

    @Test
    @DisplayName("parseResponse_동기미동기섹션을각각파싱한다")
    void parseResponse_returnsSectionedResult() throws Exception {
        String payload = "{"
                + "\"synced\":{\"summary\":\"동기화 요약\",\"highlights\":[\"A\"],\"risks\":[\"B\"],\"nextActions\":[\"C\"]},"
                + "\"unsynced\":{\"summary\":\"미동기화 요약\",\"highlights\":[\"D\"],\"risks\":[],\"nextActions\":[\"E\"]}"
                + "}";
        JsonNode root = objectMapper.readTree("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                + objectMapper.writeValueAsString(payload)
                + "}]}}]}");

        WeeklySummarySectionsResult result = (WeeklySummarySectionsResult) invokePrivate(
                generator,
                "parseResponse",
                new Class[]{JsonNode.class, int.class, int.class},
                root,
                1,
                1
        );

        assertEquals("동기화 요약", result.getSynced().getSummary());
        assertEquals("미동기화 요약", result.getUnsynced().getSummary());
        assertEquals("gemini-2.5-flash", result.getSynced().getModel());
    }

    @Test
    @DisplayName("prepareRequest_동일입력은안정적인metrics를생성하고입력이커지면길이가증가한다")
    void prepareRequest_generatesStableMetricsAndGrowsWithInput() throws Exception {
        LocalDate weekStart = LocalDate.of(2026, 3, 23);
        LocalDate weekEnd = LocalDate.of(2026, 3, 29);
        List<SummaryTaskSnapshot> smallSynced = List.of(snapshot(task(
                "주말 장보기 일정",
                "토요일 오전에 장을 봐야 한다. 우유와 달걀, 채소를 사야 한다.",
                TaskStatus.IN_PROGRESS
        ), TaskSyncState.SYNCED));
        List<SummaryTaskSnapshot> smallUnsynced = List.of(snapshot(task(
                "봄 옷장 정리",
                "외투를 정리하고 겨울 옷은 압축팩에 넣는다.",
                TaskStatus.REQUESTED
        ), TaskSyncState.SYNC_DISABLED));

        Object firstPrepared = invokePrivate(
                generator,
                "prepareRequest",
                new Class[]{Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class},
                project,
                smallSynced,
                1,
                smallUnsynced,
                1,
                weekStart,
                weekEnd
        );
        Object secondPrepared = invokePrivate(
                generator,
                "prepareRequest",
                new Class[]{Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class},
                project,
                smallSynced,
                1,
                smallUnsynced,
                1,
                weekStart,
                weekEnd
        );

        Object firstMetrics = invokeGetter(firstPrepared, "getMetrics");
        Object secondMetrics = invokeGetter(secondPrepared, "getMetrics");

        assertEquals(invokeGetter(firstMetrics, "getRequestBodyLength"), invokeGetter(secondMetrics, "getRequestBodyLength"));
        assertEquals(invokeGetter(firstMetrics, "getPromptInputFingerprint"), invokeGetter(secondMetrics, "getPromptInputFingerprint"));
        assertEquals(1, invokeGetter(firstMetrics, "getSyncedIncludedTaskCount"));
        assertEquals(1, invokeGetter(firstMetrics, "getUnsyncedIncludedTaskCount"));

        List<SummaryTaskSnapshot> largeSynced = List.of(
                snapshot(task("주말 장보기 일정", "토요일 오전에 장을 봐야 한다. 우유와 달걀, 채소를 사야 한다. 예산도 점검한다.", TaskStatus.IN_PROGRESS), TaskSyncState.SYNCED),
                snapshot(task("가족 식사 예약", "저녁 식사 예약을 잡고 메뉴 후보를 정한다. 부모님과 시간도 다시 확인한다.", TaskStatus.REQUESTED), TaskSyncState.SYNCED)
        );
        List<SummaryTaskSnapshot> largeUnsynced = List.of(
                snapshot(task("봄 옷장 정리", "외투를 정리하고 겨울 옷은 압축팩에 넣는다. 세탁이 필요한 옷도 분리한다.", TaskStatus.REQUESTED), TaskSyncState.SYNC_DISABLED),
                snapshot(task("자전거 점검", "브레이크와 타이어를 점검해야 한다. 문제가 있으면 수리점에 맡겨야 한다.", TaskStatus.BLOCKED), TaskSyncState.SYNC_DISABLED)
        );

        Object largerPrepared = invokePrivate(
                generator,
                "prepareRequest",
                new Class[]{Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class},
                project,
                largeSynced,
                2,
                largeUnsynced,
                2,
                weekStart,
                weekEnd
        );
        Object largerMetrics = invokeGetter(largerPrepared, "getMetrics");

        assertTrue((Integer) invokeGetter(largerMetrics, "getRequestBodyLength")
                > (Integer) invokeGetter(firstMetrics, "getRequestBodyLength"));
        assertTrue((Integer) invokeGetter(largerMetrics, "getSyncedDescBriefChars")
                >= (Integer) invokeGetter(firstMetrics, "getSyncedDescBriefChars"));
        assertTrue((Integer) invokeGetter(largerMetrics, "getUnsyncedDescBriefChars")
                >= (Integer) invokeGetter(firstMetrics, "getUnsyncedDescBriefChars"));
    }

    @Test
    @DisplayName("classifyUpstreamFailure_실패로그에관측metrics를남긴다")
    void classifyUpstreamFailure_logsMetricsOnFailure(CapturedOutput output) throws Exception {
        LocalDate weekStart = LocalDate.of(2026, 3, 23);
        LocalDate weekEnd = LocalDate.of(2026, 3, 29);
        Object prepared = invokePrivate(
                generator,
                "prepareRequest",
                new Class[]{Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class},
                project,
                List.of(snapshot(task("주말 장보기 일정", "토요일 오전에 장을 보고 장보기 목록을 점검한다.", TaskStatus.IN_PROGRESS), TaskSyncState.SYNCED)),
                1,
                List.of(snapshot(task("봄 옷장 정리", "외투를 정리하고 압축팩을 준비한다.", TaskStatus.REQUESTED), TaskSyncState.SYNC_DISABLED)),
                1,
                weekStart,
                weekEnd
        );
        Object metrics = invokeGetter(prepared, "getMetrics");

        Object exception = invokePrivate(
                generator,
                "classifyUpstreamFailure",
                new Class[]{Project.class, LocalDate.class, LocalDate.class, int.class, String.class, long.class, metrics.getClass()},
                project,
                weekStart,
                weekEnd,
                429,
                "{\"error\":\"quota exceeded\"}",
                700L,
                metrics
        );

        WeeklySummaryGenerationException generationException = assertInstanceOf(WeeklySummaryGenerationException.class, exception);
        assertEquals(ErrorCode.LLM_QUOTA_EXCEEDED, generationException.getErrorCode());
        assertTrue(output.getOut().contains("Gemini summary request failed."));
        assertTrue(output.getOut().contains("requestBodyLength="));
        assertTrue(output.getOut().contains("promptInputFingerprint="));
        assertTrue(output.getOut().contains("syncedDescBriefChars="));
        assertTrue(output.getOut().contains("unsyncedDescBriefChars="));
        assertTrue(output.getOut().contains("errorCode=LLM_QUOTA_EXCEEDED"));
    }

    @Test
    @DisplayName("logUsage_성공로그에관측metrics를남긴다")
    void logUsage_logsMetricsOnSuccess(CapturedOutput output) throws Exception {
        LocalDate weekStart = LocalDate.of(2026, 3, 23);
        LocalDate weekEnd = LocalDate.of(2026, 3, 29);
        Object prepared = invokePrivate(
                generator,
                "prepareRequest",
                new Class[]{Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class},
                project,
                List.of(snapshot(task("주말 장보기 일정", "토요일 오전에 장을 보고 예산을 확인한다.", TaskStatus.IN_PROGRESS), TaskSyncState.SYNCED)),
                1,
                List.of(snapshot(task("봄 옷장 정리", "외투를 정리하고 수납 상자를 준비한다.", TaskStatus.REQUESTED), TaskSyncState.SYNC_DISABLED)),
                1,
                weekStart,
                weekEnd
        );
        Object metrics = invokeGetter(prepared, "getMetrics");
        JsonNode root = objectMapper.readTree("{\"usageMetadata\":{\"promptTokenCount\":768,\"candidatesTokenCount\":320,\"totalTokenCount\":1088}}");

        invokePrivate(
                generator,
                "logUsage",
                new Class[]{Project.class, LocalDate.class, LocalDate.class, JsonNode.class, long.class, metrics.getClass()},
                project,
                weekStart,
                weekEnd,
                root,
                2864L,
                metrics
        );

        assertTrue(output.getOut().contains("Gemini summary request succeeded."));
        assertTrue(output.getOut().contains("requestBodyLength="));
        assertTrue(output.getOut().contains("promptInputFingerprint="));
        assertTrue(output.getOut().contains("promptTokens=768"));
        assertTrue(output.getOut().contains("totalTokens=1088"));
    }

    private Task task(String title, String description, TaskStatus status) {
        Task task = Task.createTask(project, title, description, null, null, LocalDateTime.now().plusDays(1), false);
        if (status != TaskStatus.REQUESTED) {
            task.changeStatus(status);
        }
        return task;
    }

    private SummaryTaskSnapshot snapshot(Task task, TaskSyncState syncState) {
        return SummaryTaskSnapshot.of(task, syncState, null, null, null);
    }

    private Object invokeGetter(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private Object invokePrivate(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
