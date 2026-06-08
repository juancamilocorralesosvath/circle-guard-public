package com.circleguard.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ExposureNotificationListenerUnitTest {

    @Test
    void shouldDispatchAndSyncForRiskStatus() {
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        LmsService lmsService = mock(LmsService.class);

        new ExposureNotificationListener(dispatcher, new ObjectMapper(), lmsService)
                .handleStatusChange("{\"anonymousId\":\"user-1\",\"status\":\"CONFIRMED\"}");

        verify(dispatcher).dispatch("user-1", "CONFIRMED");
        verify(lmsService).syncRemoteAttendance("user-1", "CONFIRMED");
    }

    @Test
    void shouldIgnoreActiveAndUnknownStatuses() {
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        LmsService lmsService = mock(LmsService.class);
        ExposureNotificationListener listener =
                new ExposureNotificationListener(dispatcher, new ObjectMapper(), lmsService);

        listener.handleStatusChange("{\"anonymousId\":\"user-1\",\"status\":\"ACTIVE\"}");
        listener.handleStatusChange("{\"anonymousId\":\"user-1\"}");
        listener.handleStatusChange("not-json");

        verify(dispatcher, never()).dispatch("user-1", "ACTIVE");
        verify(lmsService, never()).syncRemoteAttendance("user-1", "ACTIVE");
    }
}
