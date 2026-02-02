package com.taskflow.calendar.integration.googlecalendar;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class CalendarEventDto {
    private String title;           // 이벤트 제목
    private String description;     // 이벤트 설명
    private LocalDateTime startAt;  // 시작 시각
    private LocalDateTime endAt;    // 종료 시각
}