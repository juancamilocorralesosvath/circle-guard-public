package com.circleguard.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CircleFencedListenerUnitTest {

    @Test
    void shouldCancelReservationWhenLocationExists() {
        RoomReservationService roomReservationService = mock(RoomReservationService.class);

        new CircleFencedListener(new ObjectMapper(), roomReservationService)
                .handleCircleFenced("{\"circleId\":\"circle-1\",\"locationId\":\"room-1\"}");

        verify(roomReservationService).cancelReservation("circle-1", "room-1");
    }

    @Test
    void shouldSkipCancellationWhenLocationIsMissing() {
        RoomReservationService roomReservationService = mock(RoomReservationService.class);

        new CircleFencedListener(new ObjectMapper(), roomReservationService)
                .handleCircleFenced("{\"circleId\":\"circle-1\",\"locationId\":\"\"}");

        verify(roomReservationService, never()).cancelReservation("circle-1", "");
    }

    @Test
    void shouldIgnoreMalformedEvent() {
        RoomReservationService roomReservationService = mock(RoomReservationService.class);

        new CircleFencedListener(new ObjectMapper(), roomReservationService)
                .handleCircleFenced("not-json");

        verify(roomReservationService, never()).cancelReservation(null, null);
    }
}
