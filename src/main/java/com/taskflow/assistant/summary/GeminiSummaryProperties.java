package com.taskflow.assistant.summary;

import com.taskflow.assistant.GeminiProperties;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "gemini.summary")
public class GeminiSummaryProperties extends GeminiProperties {

    private Integer topK;
    private Double topP;
}
