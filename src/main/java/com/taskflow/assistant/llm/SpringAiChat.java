package com.taskflow.assistant.llm;

import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

final class SpringAiChat {

    private SpringAiChat() {}

    static LlmResponse call(ChatModel model, LlmRequest request, ChatOptions options) {
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(request.systemInstruction()), new UserMessage(request.userPrompt())),
                options);
        ChatResponse response = model.call(prompt);
        Generation generation = response.getResult();
        Usage usage = response.getMetadata().getUsage();
        return new LlmResponse(
                text(generation),
                response.getMetadata().getModel(),
                finishReason(generation),
                tokens(usage.getPromptTokens()),
                tokens(usage.getCompletionTokens()),
                tokens(usage.getTotalTokens()));
    }

    private static String text(Generation generation) {
        if (generation == null || generation.getOutput() == null) {
            return "";
        }
        String text = generation.getOutput().getText();
        return text == null ? "" : text;
    }

    private static String finishReason(Generation generation) {
        if (generation == null) {
            return null;
        }
        ChatGenerationMetadata metadata = generation.getMetadata();
        return metadata == null ? null : metadata.getFinishReason();
    }

    private static int tokens(Integer value) {
        return value == null ? 0 : value;
    }
}
