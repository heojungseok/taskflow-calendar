package com.taskflow.assistant.llm;

import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

final class NoRetry {

    private NoRetry() {}

    static RetryTemplate template() {
        return new RetryTemplate(RetryPolicy.withMaxRetries(0));
    }
}
