package com.taskflow.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

class GeminiModelDefaultsTest {

    @DisplayName("환경 변수가 없으면 운영 기본값으로 GA 모델 gemini-3.5-flash-lite를 쓴다")
    @ParameterizedTest
    @ValueSource(strings = {"gemini.summary.model", "gemini.recommendation.model", "gemini.search.model"})
    void defaultModelIsGa(String key) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);

        String model = new PropertySourcesPropertyResolver(sources).getProperty(key);

        assertEquals("gemini-3.5-flash-lite", model);
    }
}
