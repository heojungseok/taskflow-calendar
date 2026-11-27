package com.taskflow.assistant.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.taskflow.assistant.llm.FakeLlmProvider;
import com.taskflow.assistant.llm.LlmCallException;
import com.taskflow.assistant.llm.LlmFeature;
import com.taskflow.assistant.llm.LlmRequest;
import com.taskflow.assistant.llm.LlmResponse;
import com.taskflow.assistant.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.assistant.summary.exception.WeeklySummaryGenerationException;
import com.taskflow.assistant.summary.generator.LlmWeeklySummaryGenerator;
import com.taskflow.assistant.summary.generator.TaskDescriptionBrief;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.TimeZones;
import com.taskflow.observability.TaskFlowMetrics;
import com.taskflow.planning.project.Project;
import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskStatus;
import com.taskflow.planning.task.TaskSyncState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
class LlmWeeklySummaryGeneratorTest {

    private LlmWeeklySummaryGenerator generator;
    private final FakeLlmProvider provider = new FakeLlmProvider();
    private ObjectMapper objectMapper;
    private Project project;

    private GeminiSummaryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GeminiSummaryProperties();
        properties.setApiKey("test-key");
        properties.setModel("gemini-2.5-flash");
        properties.setBaseUrl("https://example.com/v1beta");
        properties.setTimeoutSeconds(5);
        properties.setTemperature(0.2);

        objectMapper = new ObjectMapper();
        generator = new LlmWeeklySummaryGenerator(
                properties,
                objectMapper,
                new TaskFlowMetrics(new SimpleMeterRegistry()),
                Clock.system(TimeZones.SERVICE_ZONE),
                provider.client());
        project = Project.of("TaskFlow", 1L);
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
                false);

        String compressed = TaskDescriptionBrief.compressDescription(task);

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
        LlmResponse response = new LlmResponse(payload, "gemini-2.5-flash", "STOP", 0, 0, 0);

        WeeklySummarySectionsResult result = (WeeklySummarySectionsResult) invokePrivate(
                ReflectionTestUtils.getField(generator, "responseFormat"),
                "parseResponse",
                new Class[] {LlmResponse.class, int.class, int.class},
                response,
                1,
                1);

        assertEquals("동기화 요약", result.getSynced().getSummary());
        assertEquals("미동기화 요약", result.getUnsynced().getSummary());
        assertEquals("gemini-2.5-flash", result.getSynced().getModel());
    }

    @Test
    @DisplayName("prepareRequest_동일입력은안정적인metrics를생성하고입력이커지면길이가증가한다")
    void prepareRequest_generatesStableMetricsAndGrowsWithInput() throws Exception {
        LocalDate weekStart = LocalDate.of(2026, 3, 23);
        LocalDate weekEnd = LocalDate.of(2026, 3, 29);
        List<TaskSyncSnapshot> smallSynced = List.of(snapshot(
                task("주말 장보기 일정", "토요일 오전에 장을 봐야 한다. 우유와 달걀, 채소를 사야 한다.", TaskStatus.IN_PROGRESS),
                TaskSyncState.SYNCED));
        List<TaskSyncSnapshot> smallUnsynced = List.of(snapshot(
                task("봄 옷장 정리", "외투를 정리하고 겨울 옷은 압축팩에 넣는다.", TaskStatus.REQUESTED), TaskSyncState.SYNC_DISABLED));

        Object firstPrepared = invokePrivate(
                ReflectionTestUtils.getField(generator, "promptBuilder"),
                "prepareRequest",
                new Class[] {
                    Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class
                },
                project,
                smallSynced,
                1,
                smallUnsynced,
                1,
                weekStart,
                weekEnd);
        Object secondPrepared = invokePrivate(
                ReflectionTestUtils.getField(generator, "promptBuilder"),
                "prepareRequest",
                new Class[] {
                    Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class
                },
                project,
                smallSynced,
                1,
                smallUnsynced,
                1,
                weekStart,
                weekEnd);

        Object firstMetrics = invokeGetter(firstPrepared, "getMetrics");
        Object secondMetrics = invokeGetter(secondPrepared, "getMetrics");

        assertEquals(
                invokeGetter(firstMetrics, "getRequestBodyLength"),
                invokeGetter(secondMetrics, "getRequestBodyLength"));
        assertEquals(
                invokeGetter(firstMetrics, "getPromptInputFingerprint"),
                invokeGetter(secondMetrics, "getPromptInputFingerprint"));
        assertEquals(1, invokeGetter(firstMetrics, "getSyncedIncludedTaskCount"));
        assertEquals(1, invokeGetter(firstMetrics, "getUnsyncedIncludedTaskCount"));

        List<TaskSyncSnapshot> largeSynced = List.of(
                snapshot(
                        task("주말 장보기 일정", "토요일 오전에 장을 봐야 한다. 우유와 달걀, 채소를 사야 한다. 예산도 점검한다.", TaskStatus.IN_PROGRESS),
                        TaskSyncState.SYNCED),
                snapshot(
                        task("가족 식사 예약", "저녁 식사 예약을 잡고 메뉴 후보를 정한다. 부모님과 시간도 다시 확인한다.", TaskStatus.REQUESTED),
                        TaskSyncState.SYNCED));
        List<TaskSyncSnapshot> largeUnsynced = List.of(
                snapshot(
                        task("봄 옷장 정리", "외투를 정리하고 겨울 옷은 압축팩에 넣는다. 세탁이 필요한 옷도 분리한다.", TaskStatus.REQUESTED),
                        TaskSyncState.SYNC_DISABLED),
                snapshot(
                        task("자전거 점검", "브레이크와 타이어를 점검해야 한다. 문제가 있으면 수리점에 맡겨야 한다.", TaskStatus.BLOCKED),
                        TaskSyncState.SYNC_DISABLED));

        Object largerPrepared = invokePrivate(
                ReflectionTestUtils.getField(generator, "promptBuilder"),
                "prepareRequest",
                new Class[] {
                    Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class
                },
                project,
                largeSynced,
                2,
                largeUnsynced,
                2,
                weekStart,
                weekEnd);
        Object largerMetrics = invokeGetter(largerPrepared, "getMetrics");

        assertTrue((Integer) invokeGetter(largerMetrics, "getRequestBodyLength")
                > (Integer) invokeGetter(firstMetrics, "getRequestBodyLength"));
        assertTrue((Integer) invokeGetter(largerMetrics, "getSyncedDescBriefChars")
                >= (Integer) invokeGetter(firstMetrics, "getSyncedDescBriefChars"));
        assertTrue((Integer) invokeGetter(largerMetrics, "getUnsyncedDescBriefChars")
                >= (Integer) invokeGetter(firstMetrics, "getUnsyncedDescBriefChars"));
    }

    @Test
    @DisplayName("prepareRequest_topK와topP가유효하면요청에포함한다")
    void prepareRequest_includesTopKAndTopPWhenValid() throws Exception {
        generatorProperties().setTopK(20);
        generatorProperties().setTopP(0.8d);

        Object prepared = invokePrivate(
                ReflectionTestUtils.getField(generator, "promptBuilder"),
                "prepareRequest",
                new Class[] {
                    Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class
                },
                project,
                List.of(snapshot(
                        task("주말 장보기 일정", "토요일 오전에 장을 보고 예산을 확인한다.", TaskStatus.IN_PROGRESS), TaskSyncState.SYNCED)),
                1,
                List.of(snapshot(
                        task("봄 옷장 정리", "외투를 정리하고 수납 상자를 준비한다.", TaskStatus.REQUESTED), TaskSyncState.SYNC_DISABLED)),
                1,
                LocalDate.of(2026, 3, 23),
                LocalDate.of(2026, 3, 29));

        LlmRequest request = preparedRequest(prepared);
        assertEquals(20, request.topK());
        assertEquals(0.8d, request.topP());
    }

    @Test
    @DisplayName("prepareRequest_기능과온도와스키마와시스템지시를요청에담는다")
    void prepareRequest_carriesFeatureTemperatureSchemaAndSystemInstruction() throws Exception {
        Object prepared = invokePrivate(
                ReflectionTestUtils.getField(generator, "promptBuilder"),
                "prepareRequest",
                new Class[] {
                    Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class
                },
                project,
                List.of(snapshot(
                        task("주말 장보기 일정", "토요일 오전에 장을 보고 예산을 확인한다.", TaskStatus.IN_PROGRESS), TaskSyncState.SYNCED)),
                1,
                List.of(),
                0,
                LocalDate.of(2026, 3, 23),
                LocalDate.of(2026, 3, 29));

        LlmRequest request = preparedRequest(prepared);
        assertEquals(LlmFeature.WEEKLY_SUMMARY, request.feature());
        assertEquals(0.2d, request.temperature());
        assertNull(request.topK());
        assertNull(request.topP());
        assertTrue(request.systemInstruction().startsWith("당신은 프로젝트 업무를 주간 단위로 정리하는 요약 보조자다."));
        assertTrue(request.systemInstruction()
                .contains("summary, highlights, risks, nextActions의 문자열 값은 모두 한국어 존댓말로 작성하라."));
        assertEquals(
                List.of("synced", "unsynced"),
                objectMapper
                        .valueToTree(request.responseSchema())
                        .path("required")
                        .valueStream()
                        .map(JsonNode::asString)
                        .toList());
    }

    @Test
    @DisplayName("prepareRequest_topK와topP가유효하지않으면요청에서생략한다")
    void prepareRequest_omitsTopKAndTopPWhenInvalid() throws Exception {
        generatorProperties().setTopK(0);
        generatorProperties().setTopP(1.5d);

        Object prepared = invokePrivate(
                ReflectionTestUtils.getField(generator, "promptBuilder"),
                "prepareRequest",
                new Class[] {
                    Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class
                },
                project,
                List.of(snapshot(
                        task("주말 장보기 일정", "토요일 오전에 장을 보고 예산을 확인한다.", TaskStatus.IN_PROGRESS), TaskSyncState.SYNCED)),
                1,
                List.of(snapshot(
                        task("봄 옷장 정리", "외투를 정리하고 수납 상자를 준비한다.", TaskStatus.REQUESTED), TaskSyncState.SYNC_DISABLED)),
                1,
                LocalDate.of(2026, 3, 23),
                LocalDate.of(2026, 3, 29));

        LlmRequest request = preparedRequest(prepared);
        assertNull(request.topK());
        assertNull(request.topP());
    }

    @Test
    @DisplayName("prepareRequest_partialCoverage면대표subset가이드를추가한다")
    void prepareRequest_addsCoverageGuidanceWhenCoverageIsPartial() throws Exception {
        LocalDate weekStart = LocalDate.of(2026, 3, 23);
        LocalDate weekEnd = LocalDate.of(2026, 3, 29);

        Object prepared = invokePrivate(
                ReflectionTestUtils.getField(generator, "promptBuilder"),
                "prepareRequest",
                new Class[] {
                    Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class
                },
                project,
                List.of(snapshot(
                        task("주말 장보기 일정", "토요일 오전에 장을 보고 예산을 확인한다.", TaskStatus.IN_PROGRESS), TaskSyncState.SYNCED)),
                3,
                List.of(snapshot(
                        task("봄 옷장 정리", "외투를 정리하고 수납 상자를 준비한다.", TaskStatus.REQUESTED), TaskSyncState.SYNC_DISABLED)),
                1,
                weekStart,
                weekEnd);

        LlmRequest request = preparedRequest(prepared);
        String instructions = request.userPrompt();

        assertTrue(instructions.contains(
                "summary는 status·syncState·outbox 결과만 사실로 사용하고, 완료·성공적·순조·문제없음·위험없음 같은 표현은 근거 없으면 쓰지 마라."));
        assertTrue(instructions.contains("summary와 목록 문장은 모두 한국어 존댓말로 작성하라."));
        assertTrue(instructions.contains("제공된 tasks만 우선순위 대표 업무로 보고"));
        assertTrue(instructions.contains("우선순위 대표 업무 기준"));
        assertTrue(instructions.contains("섹션 전체를 단정하지 마라"));
        assertFalse(instructions.contains("첫 문장은 섹션 전체 경향을 먼저 요약"));
        JsonNode syncedSection = userPromptPayload(request).path("synced");
        assertEquals(3, syncedSection.path("totalTaskCount").asInt());
        assertEquals(1, syncedSection.path("includedTaskCount").asInt());
        assertEquals(
                "첫 문장부터 우선순위 대표 업무 기준으로 핵심 일정과 리스크를 요약하고, 이후 문장에서 대표 task를 연결한다.",
                syncedSection.path("summaryLeadHint").asString());
        assertEquals(
                "세부 요약은 우선순위 대표 업무만 근거로 삼고, 포함되지 않은 task까지 확장하지 않는다.",
                syncedSection.path("coverageHint").asString());

        JsonNode unsyncedSection = userPromptPayload(request).path("unsynced");
        assertEquals(
                "첫 문장은 미동기화 업무 전반의 상태를 요약하고, 이후 문장에서 주요 task를 연결한다.",
                unsyncedSection.path("summaryLeadHint").asString());
        assertTrue(unsyncedSection.path("coverageHint").isMissingNode());
    }

    @Test
    @DisplayName("prepareRequest_fullCoverage면대표subset가이드를추가하지않는다")
    void prepareRequest_omitsCoverageGuidanceWhenCoverageIsFull() throws Exception {
        LocalDate weekStart = LocalDate.of(2026, 3, 23);
        LocalDate weekEnd = LocalDate.of(2026, 3, 29);

        Object prepared = invokePrivate(
                ReflectionTestUtils.getField(generator, "promptBuilder"),
                "prepareRequest",
                new Class[] {
                    Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class
                },
                project,
                List.of(snapshot(
                        task("주말 장보기 일정", "토요일 오전에 장을 보고 예산을 확인한다.", TaskStatus.IN_PROGRESS), TaskSyncState.SYNCED)),
                1,
                List.of(snapshot(
                        task("봄 옷장 정리", "외투를 정리하고 수납 상자를 준비한다.", TaskStatus.REQUESTED), TaskSyncState.SYNC_DISABLED)),
                1,
                weekStart,
                weekEnd);

        LlmRequest request = preparedRequest(prepared);
        String instructions = request.userPrompt();
        assertTrue(instructions.contains(
                "summary는 status·syncState·outbox 결과만 사실로 사용하고, 완료·성공적·순조·문제없음·위험없음 같은 표현은 근거 없으면 쓰지 마라."));
        assertTrue(instructions.contains("summary와 목록 문장은 모두 한국어 존댓말로 작성하라."));
        assertFalse(instructions.contains("우선순위 대표 업무"));
        assertTrue(instructions.contains("첫 문장은 섹션 전체 경향을 먼저 요약"));

        JsonNode payload = userPromptPayload(request);
        assertTrue(payload.path("synced").path("coverageHint").isMissingNode());
        assertTrue(payload.path("unsynced").path("coverageHint").isMissingNode());
        assertEquals(
                "첫 문장은 이번 주 일정 전반을 요약하고, 이후 문장에서 주요 일정과 리스크를 연결한다.",
                payload.path("synced").path("summaryLeadHint").asString());
        assertEquals(
                "첫 문장은 미동기화 업무 전반의 상태를 요약하고, 이후 문장에서 주요 task를 연결한다.",
                payload.path("unsynced").path("summaryLeadHint").asString());
    }

    @Test
    @DisplayName("prepareRequest_unsynced가partialCoverage면전체경향우선힌트를넣는다")
    void prepareRequest_addsUnsyncedLeadHintWhenUnsyncedCoverageIsPartial() throws Exception {
        LocalDate weekStart = LocalDate.of(2026, 3, 23);
        LocalDate weekEnd = LocalDate.of(2026, 3, 29);

        Object prepared = invokePrivate(
                ReflectionTestUtils.getField(generator, "promptBuilder"),
                "prepareRequest",
                new Class[] {
                    Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class
                },
                project,
                List.of(snapshot(
                        task("주말 장보기 일정", "토요일 오전에 장을 보고 예산을 확인한다.", TaskStatus.IN_PROGRESS), TaskSyncState.SYNCED)),
                1,
                List.of(snapshot(
                        task("봄 옷장 정리", "외투를 정리하고 수납 상자를 준비한다.", TaskStatus.REQUESTED), TaskSyncState.SYNC_DISABLED)),
                4,
                weekStart,
                weekEnd);

        JsonNode payload = userPromptPayload(preparedRequest(prepared));
        JsonNode unsyncedSection = payload.path("unsynced");

        assertEquals(
                "첫 문장부터 우선순위 대표 업무 기준으로 누락 위험과 반영 필요 사항을 요약하고, 이후 문장에서 대표 task를 연결한다.",
                unsyncedSection.path("summaryLeadHint").asString());
        assertEquals(
                "세부 요약은 우선순위 대표 업무만 근거로 삼고, 포함되지 않은 task까지 확장하지 않는다.",
                unsyncedSection.path("coverageHint").asString());
    }

    @Test
    @DisplayName("Gemini 호출이 실패하면 분류 결과를 요약 예외로 넘기고 실패 로그에 관측 metrics를 남긴다")
    void upstreamFailure_carriesClassificationAndLogsMetrics(CapturedOutput output) {
        provider.failWith(new LlmCallException(
                ErrorCode.LLM_RATE_LIMITED_TEMPORARY,
                "Gemini rate limit is temporary",
                429,
                700L,
                "HEADER",
                "120",
                null,
                List.of("retry-after")));

        WeeklySummaryGenerationException exception = assertThrows(
                WeeklySummaryGenerationException.class,
                () -> generator.generate(
                        project,
                        List.of(snapshot(
                                task("주말 장보기 일정", "토요일 오전에 장을 보고 장보기 목록을 점검한다.", TaskStatus.IN_PROGRESS),
                                TaskSyncState.SYNCED)),
                        1,
                        List.of(snapshot(
                                task("봄 옷장 정리", "외투를 정리하고 압축팩을 준비한다.", TaskStatus.REQUESTED),
                                TaskSyncState.SYNC_DISABLED)),
                        1,
                        LocalDate.of(2026, 3, 23),
                        LocalDate.of(2026, 3, 29)));

        assertEquals(ErrorCode.LLM_RATE_LIMITED_TEMPORARY, exception.getErrorCode());
        assertEquals("HEADER", exception.getClassificationSource());
        assertEquals("120", exception.getRetryAfter());
        assertTrue(exception.isFallbackEligible());
        assertTrue(output.getOut().contains("Gemini summary request failed."));
        assertTrue(output.getOut().contains("statusCode=429"));
        assertTrue(output.getOut().contains("promptInputFingerprint="));
        assertTrue(output.getOut().contains("unsyncedDescBriefChars="));
        assertTrue(output.getOut().contains("errorCode=LLM_RATE_LIMITED_TEMPORARY"));
        assertTrue(output.getOut().contains("classificationSource=HEADER"));
    }

    @Test
    @DisplayName("성공하면 응답의 모델과 토큰 수를 읽어 관측 metrics와 함께 로그에 남긴다")
    void generate_logsMetricsOnSuccess(CapturedOutput output) {
        provider.respondWith(new LlmResponse(validPayload(), "gemini-served-model", "STOP", 768, 320, 1088));

        WeeklySummarySectionsResult result = generator.generate(
                project, oneSynced(), 1, oneUnsynced(), 1, LocalDate.of(2026, 3, 23), LocalDate.of(2026, 3, 29));

        assertTrue(output.getOut().contains("Gemini summary request succeeded."));
        assertTrue(output.getOut().contains("model=gemini-served-model"));
        LlmRequest sent = provider.lastRequest();
        int promptLength =
                (sent.systemInstruction() + sent.userPrompt()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(output.getOut().contains("requestBodyLength=" + promptLength));
        assertTrue(output.getOut().contains("promptInputFingerprint="));
        assertTrue(output.getOut().contains("promptTokens=768"));
        assertTrue(output.getOut().contains("candidateTokens=320"));
        assertTrue(output.getOut().contains("totalTokens=1088"));
        assertEquals("동기화 요약", result.getSynced().getSummary());
    }

    @Test
    @DisplayName("응답 본문이 비어 있으면 finishReason을 담아 LLM_INVALID_RESPONSE로 실패한다")
    void generate_blankTextReportsFinishReason() {
        provider.respondWith(new LlmResponse("  ", "gemini-served-model", "SAFETY", 1, 0, 1));

        WeeklySummaryGenerationException exception = assertThrows(
                WeeklySummaryGenerationException.class,
                () -> generator.generate(
                        project,
                        oneSynced(),
                        1,
                        oneUnsynced(),
                        1,
                        LocalDate.of(2026, 3, 23),
                        LocalDate.of(2026, 3, 29)));

        assertEquals(ErrorCode.LLM_INVALID_RESPONSE, exception.getErrorCode());
        assertFalse(exception.isFallbackEligible());
        assertTrue(exception.getMessage().contains("finishReason=SAFETY"));
    }

    @Test
    @DisplayName("API 키가 없다는 제공자 오류는 같은 코드로 넘기고 대체 결과 대상에서 뺀다")
    void generate_apiKeyMissingIsNotFallbackEligible() {
        provider.failWith(new LlmCallException(
                ErrorCode.LLM_API_KEY_MISSING, "Gemini API key is not configured", 0, 0, null, null, null, List.of()));

        WeeklySummaryGenerationException exception = assertThrows(
                WeeklySummaryGenerationException.class,
                () -> generator.generate(
                        project,
                        oneSynced(),
                        1,
                        oneUnsynced(),
                        1,
                        LocalDate.of(2026, 3, 23),
                        LocalDate.of(2026, 3, 29)));

        assertEquals(ErrorCode.LLM_API_KEY_MISSING, exception.getErrorCode());
        assertFalse(exception.isFallbackEligible());
    }

    private String validPayload() {
        return "{"
                + "\"synced\":{\"summary\":\"동기화 요약\",\"highlights\":[\"A\"],\"risks\":[],\"nextActions\":[\"C\"]},"
                + "\"unsynced\":{\"summary\":\"미동기화 요약\",\"highlights\":[],\"risks\":[],\"nextActions\":[\"E\"]}"
                + "}";
    }

    private List<TaskSyncSnapshot> oneSynced() {
        return List.of(
                snapshot(task("주말 장보기 일정", "토요일 오전에 장을 보고 예산을 확인한다.", TaskStatus.IN_PROGRESS), TaskSyncState.SYNCED));
    }

    private List<TaskSyncSnapshot> oneUnsynced() {
        return List.of(
                snapshot(task("봄 옷장 정리", "외투를 정리하고 수납 상자를 준비한다.", TaskStatus.REQUESTED), TaskSyncState.SYNC_DISABLED));
    }

    private GeminiSummaryProperties generatorProperties() throws Exception {
        java.lang.reflect.Field field = LlmWeeklySummaryGenerator.class.getDeclaredField("properties");
        field.setAccessible(true);
        return (GeminiSummaryProperties) field.get(generator);
    }

    private Task task(String title, String description, TaskStatus status) {
        Task task = Task.createTask(
                project, title, description, null, null, LocalDateTime.now().plusDays(1), false);
        if (status != TaskStatus.REQUESTED) {
            task.changeStatus(status);
        }
        return task;
    }

    private TaskSyncSnapshot snapshot(Task task, TaskSyncState syncState) {
        return TaskSyncSnapshot.of(task, syncState, null, null, null);
    }

    private LlmRequest preparedRequest(Object prepared) throws Exception {
        return (LlmRequest) invokeGetter(prepared, "getRequest");
    }

    private JsonNode userPromptPayload(LlmRequest request) throws Exception {
        String promptText = request.userPrompt();
        String jsonPayload = promptText.substring(promptText.indexOf('\n') + 1);
        return objectMapper.readTree(jsonPayload);
    }

    private Object invokeGetter(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private Object invokePrivate(Object target, String methodName, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    /** KST 2026-10-12(월) 08:30. UTC로는 아직 10-11(일) 23:30이라 시간대를 잘못 쓰면 날짜가 하루, 주가 한 주 밀린다. */
    private static final Clock KST_MONDAY_MORNING =
            Clock.fixed(Instant.parse("2026-10-11T23:30:00Z"), TimeZones.SERVICE_ZONE);

    @Test
    @DisplayName("prepareRequest_today와지연판정은KST날짜기준이다")
    void prepareRequest_todayAndOverdueFollowKst() throws Exception {
        LlmWeeklySummaryGenerator kstGenerator = new LlmWeeklySummaryGenerator(
                properties,
                objectMapper,
                new TaskFlowMetrics(new SimpleMeterRegistry()),
                KST_MONDAY_MORNING,
                provider.client());
        // KST 기준 어제 마감(지연)과 오늘 마감(지연 아님). UTC 날짜(10-11)로 판정하면 둘 다 지연이 아니다.
        Task yesterday =
                Task.createTask(project, "어제 마감", null, null, null, LocalDateTime.of(2026, 10, 11, 18, 0), false);
        Task today = Task.createTask(project, "오늘 마감", null, null, null, LocalDateTime.of(2026, 10, 12, 18, 0), false);

        Object prepared = invokePrivate(
                ReflectionTestUtils.getField(kstGenerator, "promptBuilder"),
                "prepareRequest",
                new Class[] {
                    Project.class, List.class, int.class, List.class, int.class, LocalDate.class, LocalDate.class
                },
                project,
                List.of(snapshot(yesterday, TaskSyncState.SYNCED), snapshot(today, TaskSyncState.SYNCED)),
                2,
                List.of(),
                0,
                LocalDate.of(2026, 10, 12),
                LocalDate.of(2026, 10, 18));

        JsonNode payload = userPromptPayload(preparedRequest(prepared));
        JsonNode tasks = payload.path("synced").path("tasks");
        assertEquals(objectMapper.valueToTree(LocalDate.of(2026, 10, 12)), payload.path("today"));
        assertTrue(taskNamed(tasks, "어제 마감").path("isOverdue").asBoolean(false));
        assertFalse(taskNamed(tasks, "오늘 마감").path("isOverdue").asBoolean(false));
    }

    private static JsonNode taskNamed(JsonNode tasks, String title) {
        for (JsonNode task : tasks) {
            if (title.equals(task.path("title").asString())) {
                return task;
            }
        }
        throw new AssertionError("prompt payload has no task: " + title);
    }
}
