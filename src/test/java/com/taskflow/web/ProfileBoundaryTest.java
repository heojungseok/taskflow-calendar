package com.taskflow.web;

import com.taskflow.calendar.integration.googlecalendar.GoogleCalendarClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ProfileBoundaryTest {

    @Test
    void calendarTestControllerExistsOnlyWithExplicitLocalProfile() {
        assertEquals(0, controllerCount(null));
        assertEquals(1, controllerCount("local"));
    }

    private int controllerCount(String profile) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            if (profile != null) {
                context.getEnvironment().setActiveProfiles(profile);
            }
            context.registerBean(GoogleCalendarClient.class, () -> mock(GoogleCalendarClient.class));
            context.register(CalendarTestController.class);
            context.refresh();
            return context.getBeansOfType(CalendarTestController.class).size();
        }
    }
}
