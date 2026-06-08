package com.circleguard.promotion.controller;

import com.circleguard.promotion.dto.AccessPointDTO;
import com.circleguard.promotion.dto.FloorDTO;
import com.circleguard.promotion.model.AccessPoint;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.service.AccessPointService;
import com.circleguard.promotion.service.FloorService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FloorAndAccessPointControllerUnitTest {

    @Test
    void shouldManageFloorAccessPoints() {
        FloorService floorService = mock(FloorService.class);
        AccessPointService accessPointService = mock(AccessPointService.class);
        FloorController controller = new FloorController(floorService, accessPointService);
        UUID floorId = UUID.randomUUID();
        AccessPoint ap = AccessPoint.builder()
                .id(UUID.randomUUID()).macAddress("aa:bb").coordinateX(1.0).coordinateY(2.0).name("AP")
                .floor(Floor.builder().id(floorId).build())
                .build();
        Floor floor = Floor.builder().id(floorId).floorNumber(2).name("Second").floorPlanUrl("plan.png").build();
        AccessPointDTO apRequest = AccessPointDTO.builder().macAddress("aa:bb").coordinateX(1.0).coordinateY(2.0).name("AP").build();
        when(accessPointService.registerAccessPoint(floorId, "aa:bb", 1.0, 2.0, "AP")).thenReturn(ap);
        when(accessPointService.getAccessPointsByFloor(floorId)).thenReturn(List.of(ap));
        when(floorService.updateFloor(floorId, 2, "Second", "plan.png")).thenReturn(floor);

        assertEquals("aa:bb", controller.addAccessPoint(floorId, apRequest).getBody().getMacAddress());
        assertEquals(1, controller.getAccessPoints(floorId).getBody().size());
        assertEquals("Second", controller.updateFloor(floorId, FloorDTO.builder().floorNumber(2).name("Second").floorPlanUrl("plan.png").build()).getBody().getName());
        assertEquals(200, controller.deleteFloor(floorId).getStatusCode().value());
        verify(floorService).deleteFloor(floorId);
    }

    @Test
    void shouldManageIndividualAccessPoint() {
        AccessPointService accessPointService = mock(AccessPointService.class);
        AccessPointController controller = new AccessPointController(accessPointService);
        UUID id = UUID.randomUUID();
        AccessPoint ap = AccessPoint.builder().id(id).macAddress("aa:bb").coordinateX(1.0).coordinateY(2.0).name("AP").build();
        when(accessPointService.getAccessPoint(id)).thenReturn(Optional.of(ap));
        when(accessPointService.getAccessPoint(UUID.nameUUIDFromBytes("missing".getBytes()))).thenReturn(Optional.empty());
        when(accessPointService.updateAccessPoint(id, "cc:dd", 3.0, 4.0, "Lab")).thenReturn(ap);

        assertEquals("aa:bb", controller.getAccessPoint(id).getBody().getMacAddress());
        assertNull(controller.getAccessPoint(UUID.nameUUIDFromBytes("missing".getBytes())).getBody());
        assertEquals(404, controller.getAccessPoint(UUID.nameUUIDFromBytes("missing".getBytes())).getStatusCode().value());
        assertEquals("AP", controller.updateAccessPoint(id, AccessPointDTO.builder().macAddress("cc:dd").coordinateX(3.0).coordinateY(4.0).name("Lab").build()).getBody().getName());
        assertEquals(200, controller.deleteAccessPoint(id).getStatusCode().value());
        verify(accessPointService).deleteAccessPoint(id);
    }
}
