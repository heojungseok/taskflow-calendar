package com.taskflow.assistant.recommendation;

import com.taskflow.assistant.GeminiProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gemini.recommendation")
public class GeminiRecommendationProperties extends GeminiProperties {}
