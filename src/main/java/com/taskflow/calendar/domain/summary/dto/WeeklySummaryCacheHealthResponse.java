package com.taskflow.calendar.domain.summary.dto;

public class WeeklySummaryCacheHealthResponse {

    private final boolean enabled;
    private final boolean healthy;
    private final String status;
    private final String key;
    private final String value;
    private final String error;

    private WeeklySummaryCacheHealthResponse(boolean enabled,
                                             boolean healthy,
                                             String status,
                                             String key,
                                             String value,
                                             String error) {
        this.enabled = enabled;
        this.healthy = healthy;
        this.status = status;
        this.key = key;
        this.value = value;
        this.error = error;
    }

    public static WeeklySummaryCacheHealthResponse disabled() {
        return new WeeklySummaryCacheHealthResponse(false, false, "DISABLED", null, null, null);
    }

    public static WeeklySummaryCacheHealthResponse healthy(String key, String value) {
        return new WeeklySummaryCacheHealthResponse(true, true, "OK", key, value, null);
    }

    public static WeeklySummaryCacheHealthResponse unhealthy(String error) {
        return new WeeklySummaryCacheHealthResponse(true, false, "UNHEALTHY", null, null, error);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public String getStatus() {
        return status;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public String getError() {
        return error;
    }
}
