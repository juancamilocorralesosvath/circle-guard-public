package com.circleguard.promotion.controller;

import com.circleguard.promotion.model.graph.CircleNode;
import com.circleguard.promotion.repository.graph.UserNodeRepository;
import com.circleguard.promotion.service.AutoCircleService;
import com.circleguard.promotion.service.CircleService;
import com.circleguard.promotion.service.LocationResolutionService;
import com.circleguard.promotion.service.MacSessionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimplePromotionControllersUnitTest {

    @Test
    void shouldHandleCircleEndpoints() {
        CircleService circleService = mock(CircleService.class);
        CircleController controller = new CircleController(circleService);
        CircleNode circle = CircleNode.builder().id(1L).name("Lab").build();
        CircleController.CircleCreateRequest request = new CircleController.CircleCreateRequest();
        request.setName("Lab");
        request.setLocationId("room-1");
        when(circleService.createCircle("Lab", "room-1")).thenReturn(circle);
        when(circleService.joinCircle("u1", "MESH-1234")).thenReturn(circle);
        when(circleService.addMember(1L, "u1")).thenReturn(circle);
        when(circleService.getUserCircles("u1")).thenReturn(List.of(circle));

        assertEquals(circle, controller.createCircle(request).getBody());
        assertEquals(circle, controller.joinCircle("MESH-1234", "u1").getBody());
        assertEquals(circle, controller.addMember(1L, "u1").getBody());
        assertEquals(1, controller.getUserCircles("u1").getBody().size());
        assertEquals(200, controller.toggleValidity(1L).getStatusCode().value());
        assertEquals(200, controller.forceFence(1L).getStatusCode().value());
        verify(circleService).toggleCircleValidity(1L);
        verify(circleService).forceFenceCircle(1L);
    }

    @Test
    void shouldHandleEncounterMeshLocationAndSessionEndpoints() {
        UserNodeRepository userRepository = mock(UserNodeRepository.class);
        AutoCircleService autoCircleService = mock(AutoCircleService.class);
        EncounterController encounterController = new EncounterController(userRepository, autoCircleService);
        EncounterController.EncounterRequest encounter = new EncounterController.EncounterRequest();
        encounter.setSourceId("u1");
        encounter.setTargetId("u2");
        assertEquals(200, encounterController.reportEncounter(encounter).getStatusCode().value());
        assertEquals(200, encounterController.toggleValidity(5L).getStatusCode().value());
        assertEquals(200, encounterController.forceFence(5L).getStatusCode().value());
        verify(userRepository).recordEncounter(eq("u1"), eq("u2"), anyLong(), eq("mobile_ble"));
        verify(autoCircleService).evaluateEncounter("u1", "u2");

        when(userRepository.getConfirmedConnectionCount("u1")).thenReturn(3L);
        when(userRepository.getUnconfirmedConnectionCount("u1")).thenReturn(4L);
        assertEquals(3L, new MeshController(userRepository).getMeshStats("u1").getBody().getConfirmedCount());

        LocationResolutionService locationService = mock(LocationResolutionService.class);
        LocationSignalController locationController = new LocationSignalController(locationService);
        assertEquals(200, locationController.receiveSignal(Map.of("apMac", "ap", "deviceMac", "dev", "rssi", -40)).getStatusCode().value());
        verify(locationService).processSignal("ap", "dev", -40.0);

        MacSessionRegistry sessionRegistry = mock(MacSessionRegistry.class);
        SessionHandshakeController sessionController = new SessionHandshakeController(sessionRegistry);
        assertEquals(200, sessionController.handshake(Map.of("macAddress", "aa:bb", "anonymousId", "u1")).getStatusCode().value());
        assertEquals(400, sessionController.handshake(Map.of("macAddress", "aa:bb")).getStatusCode().value());
        assertEquals(204, sessionController.closeSession("aa:bb").getStatusCode().value());
        verify(sessionRegistry).registerSession("aa:bb", "u1");
        verify(sessionRegistry).closeSession("aa:bb");
    }
}
