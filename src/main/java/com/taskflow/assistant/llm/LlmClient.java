package com.taskflow.assistant.llm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LlmClient {

    private final Map<String, LlmProvider> providers = new HashMap<>();
    private final LlmRoutingProperties routing;

    public LlmClient(List<LlmProvider> providers, LlmRoutingProperties routing) {
        for (LlmProvider provider : providers) {
            this.providers.put(provider.name(), provider);
        }
        this.routing = routing;
        for (String name : routing.provider().values()) {
            if (!this.providers.containsKey(name)) {
                throw new IllegalStateException("Unknown LLM provider: " + name);
            }
        }
    }

    public LlmResponse generate(LlmRequest request) {
        String name = routing.providerFor(request.feature());
        LlmProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalStateException("Unknown LLM provider: " + name);
        }
        return provider.generate(request);
    }
}
