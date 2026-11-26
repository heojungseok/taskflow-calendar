package com.taskflow.assistant.llm.bakeoff;

import java.util.concurrent.atomic.AtomicBoolean;

record Candidate(String id, String provider, String model, Generators generators, AtomicBoolean coldPending) {

    boolean isOllama() {
        return "ollama".equals(provider);
    }
}
