package com.taskflow.assistant.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("llm.ollama")
public class OllamaLlmProperties {

    private String baseUrl;
    private String model;
    private String keepAlive;
    private Integer numCtx;
    private int timeoutSeconds = 60;
}
