package com.taskflow.assistant.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FakeLlmProvider implements LlmProvider {

    private final List<LlmRequest> requests = new ArrayList<>();
    private LlmResponse response = new LlmResponse("{}", "fake-model", "STOP", 0, 0, 0);
    private LlmCallException failure;

    public static FakeLlmProvider returning(String text) {
        FakeLlmProvider provider = new FakeLlmProvider();
        provider.respondWith(new LlmResponse(text, "fake-model", "STOP", 0, 0, 0));
        return provider;
    }

    public void respondWith(LlmResponse response) {
        this.response = response;
        this.failure = null;
    }

    public void failWith(LlmCallException failure) {
        this.failure = failure;
    }

    public LlmClient client() {
        return new LlmClient(List.of(this), new LlmRoutingProperties(Map.of()));
    }

    public LlmRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    public int requestCount() {
        return requests.size();
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public String model(LlmFeature feature) {
        return "fake-model";
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        requests.add(request);
        if (failure != null) {
            throw failure;
        }
        return response;
    }
}
