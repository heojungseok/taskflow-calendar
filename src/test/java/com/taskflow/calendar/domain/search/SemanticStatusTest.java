package com.taskflow.calendar.domain.search;

import com.taskflow.config.GeminiSearchProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 상태 매핑이 이 변경의 핵심이다. 여기가 틀리면 조용한 실패가 그대로 돌아온다.
 * 특히 "켜뒀는데 키가 없다"를 DISABLED로 보고하면 화면에 아무 표시가 없어
 * 고치려던 실패 모양이 재현된다.
 */
class SemanticStatusTest {

    private TaskSearchEmbeddingService serviceWith(boolean enabled, String apiKey, boolean storeAvailable) {
        GeminiSearchProperties properties = new GeminiSearchProperties();
        properties.setSemanticEnabled(enabled);
        properties.setApiKey(apiKey);

        TaskSearchEmbeddingStore store = mock(TaskSearchEmbeddingStore.class);
        when(store.isAvailable()).thenReturn(storeAvailable);

        return new TaskSearchEmbeddingService(properties, null, store, null);
    }

    @Test
    @DisplayName("정상이면 READY")
    void readyWhenEverythingWorks() {
        assertThat(serviceWith(true, "key", true).semanticStatus())
                .isEqualTo(SemanticSearchStatus.READY);
    }

    @Test
    @DisplayName("설정으로 껐으면 DISABLED")
    void disabledWhenTurnedOff() {
        assertThat(serviceWith(false, "key", true).semanticStatus())
                .isEqualTo(SemanticSearchStatus.DISABLED);
    }

    @Test
    @DisplayName("스토어가 죽었으면 UNAVAILABLE - DISABLED로 뭉개면 화면에 안 뜬다")
    void unavailableWhenStoreIsDown() {
        assertThat(serviceWith(true, "key", false).semanticStatus())
                .isEqualTo(SemanticSearchStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("켜뒀는데 키가 비면 UNAVAILABLE - 오설정을 의도로 위장하지 않는다")
    void unavailableWhenEnabledButKeyMissing() {
        assertThat(serviceWith(true, "", true).semanticStatus())
                .isEqualTo(SemanticSearchStatus.UNAVAILABLE);
        assertThat(serviceWith(true, null, true).semanticStatus())
                .isEqualTo(SemanticSearchStatus.UNAVAILABLE);
    }
}
