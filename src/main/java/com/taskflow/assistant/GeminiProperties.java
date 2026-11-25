package com.taskflow.assistant;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GeminiProperties {

    private String apiKey;
    private String model = "gemini-3.5-flash-lite";
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
    private int timeoutSeconds = 20;
    private double temperature = 0.2;
}
