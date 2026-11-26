package com.taskflow.assistant.llm.bakeoff;

record Candidate(String id, String provider, String model, Generators generators) {

    boolean isOllama() {
        return "ollama".equals(provider);
    }
}
