package com.taskflow.calendar.integration.googlecalendar;

import com.google.api.client.util.DateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCalendarClientImplTest {

    @Test
    void taskTimeIsSentAsAsiaSeoul() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            GoogleCalendarClientImpl client = new GoogleCalendarClientImpl(null, null);

            DateTime converted = ReflectionTestUtils.invokeMethod(
                    client, "toDateTime", LocalDateTime.of(2026, 8, 20, 19, 5));

            assertThat(converted).isNotNull();
            assertThat(converted.getValue())
                    .isEqualTo(Instant.parse("2026-08-20T10:05:00Z").toEpochMilli());
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
