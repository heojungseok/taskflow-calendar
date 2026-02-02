package com.taskflow.web;

import com.taskflow.calendar.integration.googlecalendar.CalendarEventDto;
import com.taskflow.calendar.integration.googlecalendar.GoogleCalendarClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/test/calendar")
@RequiredArgsConstructor
public class CalendarTestController {

    private final GoogleCalendarClient client;

    @PostMapping("/create")
    public Map<String, String> createEvent(@RequestBody Map<String, Object> request) {
        Long userId = ((Number) request.get("userId")).longValue();

        CalendarEventDto event = CalendarEventDto.builder()
                .title((String) request.get("title"))
                .description((String) request.get("description"))
                .startAt(LocalDateTime.parse((String) request.get("startAt")))
                .endAt(LocalDateTime.parse((String) request.get("endAt")))
                .build();

        String eventId = client.createEvent(userId, event);

        return Map.of("eventId", eventId);
    }
}
