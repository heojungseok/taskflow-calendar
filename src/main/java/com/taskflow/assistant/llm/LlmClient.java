package com.taskflow.assistant.llm;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class LlmClient {

    private static final String GEMINI = "gemini";

    private final Map<String, LlmProvider> providers;
    private final LlmRoutingProperties routing;

    public LlmClient(List<LlmProvider> providers, LlmRoutingProperties routing) {
        this.providers = providers.stream().collect(Collectors.toMap(LlmProvider::name, Function.identity()));
        this.routing = routing;
        for (LlmFeature feature : LlmFeature.values()) {
            String name = routing.providerFor(feature);
            if (!this.providers.containsKey(name)) {
                throw new IllegalStateException("Unknown LLM provider: " + name);
            }
        }
    }

    public LlmResponse generate(LlmRequest request) {
        return providerFor(request.feature()).generate(request);
    }

    public String modelId(LlmFeature feature) {
        LlmProvider provider = providerFor(feature);
        return GEMINI.equals(provider.name())
                ? provider.model(feature)
                : provider.name() + ":" + provider.model(feature);
    }

    public String providerName(LlmFeature feature) {
        return providerFor(feature).name();
    }

    private LlmProvider providerFor(LlmFeature feature) {
        return providers.get(routing.providerFor(feature));
    }
}
