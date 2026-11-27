package com.taskflow.assistant.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiChatTest {

    private static final LlmRequest REQUEST =
            new LlmRequest(LlmFeature.SEARCH_INTENT, "지시", "검색어", Map.of("type", "object"), 0.2, null, null);

    @Test
    @DisplayName("system 지시와 user 프롬프트를 순서대로 보내고 옵션을 붙인다")
    void sendsSystemThenUser() {
        AtomicReference<Prompt> sent = new AtomicReference<>();
        ChatModel model = prompt -> {
            sent.set(prompt);
            return response("{\"a\":1}");
        };
        ChatOptions options = ChatOptions.builder().model("m").build();

        SpringAiChat.call(model, REQUEST, options);

        assertThat(sent.get().getInstructions())
                .extracting(m -> m.getMessageType(), m -> m.getText())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MessageType.SYSTEM, "지시"),
                        org.assertj.core.groups.Tuple.tuple(MessageType.USER, "검색어"));
        assertThat(sent.get().getOptions()).isSameAs(options);
    }

    @Test
    @DisplayName("응답 텍스트·모델·종료 사유·토큰 수를 옮긴다")
    void mapsResponse() {
        LlmResponse result = SpringAiChat.call(
                prompt -> response("{\"a\":1}"), REQUEST, ChatOptions.builder().build());

        assertThat(result).isEqualTo(new LlmResponse("{\"a\":1}", "gemma", "STOP", 10, 5, 15));
    }

    @Test
    @DisplayName("생성 결과가 없으면 빈 텍스트로 돌려준다")
    void emptyGeneration() {
        ChatResponse empty = new ChatResponse(List.of());

        LlmResponse result = SpringAiChat.call(
                prompt -> empty, REQUEST, ChatOptions.builder().build());

        assertThat(result.text()).isEmpty();
    }

    private static ChatResponse response(String text) {
        Generation generation = new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason("STOP").build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("gemma")
                .usage(new DefaultUsage(10, 5, 15))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }
}
