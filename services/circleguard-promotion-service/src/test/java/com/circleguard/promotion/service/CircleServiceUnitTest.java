package com.circleguard.promotion.service;

import com.circleguard.promotion.model.graph.CircleNode;
import com.circleguard.promotion.model.graph.UserNode;
import com.circleguard.promotion.repository.graph.CircleNodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CircleServiceUnitTest {

    private final CircleNodeRepository circleRepository = mock(CircleNodeRepository.class);
    private final HealthStatusService healthStatusService = mock(HealthStatusService.class);
    private final CircleService service = new CircleService(circleRepository, healthStatusService);

    @Test
    void shouldToggleCircleValidityAndResolveMembersWhenInvalidated() {
        CircleNode circle = CircleNode.builder()
                .id(1L)
                .isValid(true)
                .members(Set.of(UserNode.builder().anonymousId("u1").build()))
                .build();
        when(circleRepository.findById(1L)).thenReturn(Optional.of(circle));

        service.toggleCircleValidity(1L);

        assertFalse(circle.getIsValid());
        verify(circleRepository).save(circle);
        verify(healthStatusService).resolveStatus("u1");
    }

    @Test
    void shouldForceFenceOnlyActiveMembers() {
        CircleNode circle = CircleNode.builder()
                .id(1L)
                .name("Lab")
                .members(Set.of(
                        UserNode.builder().anonymousId("active").status("ACTIVE").build(),
                        UserNode.builder().anonymousId("suspect").status("SUSPECT").build()))
                .build();
        when(circleRepository.findById(1L)).thenReturn(Optional.of(circle));

        service.forceFenceCircle(1L);

        assertTrue(circle.getForceFence());
        verify(healthStatusService).updateStatus("active", "PROBABLE");
    }

    @Test
    void shouldCreateJoinAddAndListCircles() {
        CircleNode circle = CircleNode.builder().id(1L).name("Lab").build();
        when(circleRepository.existsByInviteCode(anyString())).thenReturn(false);
        when(circleRepository.save(any(CircleNode.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(circleRepository.joinCircle("u1", "MESH-1234")).thenReturn(Optional.of(circle));
        when(circleRepository.findCirclesByUser("u1")).thenReturn(List.of());
        when(circleRepository.addUserToCircle("u1", 1L)).thenReturn(Optional.of(circle));
        when(circleRepository.findCirclesByUser("u2")).thenReturn(List.of(circle));

        CircleNode created = service.createCircle("Lab", "room-1");

        assertEquals("Lab", created.getName());
        assertTrue(created.getInviteCode().startsWith("MESH-"));
        assertEquals(circle, service.joinCircle("u1", "MESH-1234"));
        assertEquals(circle, service.addMember(1L, "u1"));
        assertEquals(List.of(circle), service.getUserCircles("u2"));
    }

    @Test
    void shouldRejectMissingAndDuplicateCircleOperations() {
        CircleNode existing = CircleNode.builder().id(1L).build();
        when(circleRepository.findById(99L)).thenReturn(Optional.empty());
        when(circleRepository.joinCircle("u1", "bad")).thenReturn(Optional.empty());
        when(circleRepository.findCirclesByUser("u1")).thenReturn(List.of(existing));
        when(circleRepository.addUserToCircle("u2", 2L)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service.toggleCircleValidity(99L));
        assertThrows(RuntimeException.class, () -> service.forceFenceCircle(99L));
        assertThrows(RuntimeException.class, () -> service.joinCircle("u1", "bad"));
        assertThrows(IllegalStateException.class, () -> service.addMember(1L, "u1"));
        assertThrows(IllegalArgumentException.class, () -> service.addMember(2L, "u2"));
    }
}
