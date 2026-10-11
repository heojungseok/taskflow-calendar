package com.taskflow.calendarsync.integration.googlecalendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.client.util.DateTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GoogleCalendarClientTimeZoneTest {

    @Test
    void taskTimeIsSentAsAsiaSeoul() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            GoogleCalendarClient client = new GoogleCalendarClient(null, null);

            DateTime converted =
                    ReflectionTestUtils.invokeMethod(client, "toDateTime", LocalDateTime.of(2026, 8, 20, 19, 5));

            assertThat(converted).isNotNull();
            assertThat(converted.getValue())
                    .isEqualTo(Instant.parse("2026-08-20T10:05:00Z").toEpochMilli());
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
