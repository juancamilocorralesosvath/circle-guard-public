package com.circleguard.promotion.service;

import com.circleguard.promotion.model.AccessPoint;
import com.circleguard.promotion.model.Building;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.repository.jpa.AccessPointRepository;
import com.circleguard.promotion.repository.jpa.BuildingRepository;
import com.circleguard.promotion.repository.jpa.FloorRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FloorServiceExtraTest {

    @Test
    void shouldAddUpdateListAndDeleteFloor() {
        BuildingRepository buildingRepository = mock(BuildingRepository.class);
        FloorRepository floorRepository = mock(FloorRepository.class);
        AccessPointRepository accessPointRepository = mock(AccessPointRepository.class);
        UUID buildingId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        Building building = Building.builder().id(buildingId).build();
        Floor floor = Floor.builder().id(floorId).floorNumber(1).name("First").build();
        when(buildingRepository.findById(buildingId)).thenReturn(Optional.of(building));
        when(floorRepository.save(any(Floor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(floorRepository.findByBuildingId(buildingId)).thenReturn(List.of(floor));
        when(floorRepository.findById(floorId)).thenReturn(Optional.of(floor));
        when(accessPointRepository.findByFloorId(floorId)).thenReturn(List.of());
        FloorService service = new FloorService(buildingRepository, floorRepository, accessPointRepository);

        assertEquals(building, service.addFloor(buildingId, 1, "First").getBuilding());
        assertEquals(List.of(floor), service.getFloorsByBuilding(buildingId));
        assertEquals("Second", service.updateFloor(floorId, 2, "Second", "plan.png").getName());
        service.deleteFloor(floorId);

        verify(floorRepository).deleteById(floorId);
    }

    @Test
    void shouldRejectMissingFloorOrBuildingAndDeleteWithAccessPoints() {
        BuildingRepository buildingRepository = mock(BuildingRepository.class);
        FloorRepository floorRepository = mock(FloorRepository.class);
        AccessPointRepository accessPointRepository = mock(AccessPointRepository.class);
        UUID id = UUID.randomUUID();
        when(buildingRepository.findById(id)).thenReturn(Optional.empty());
        when(floorRepository.findById(id)).thenReturn(Optional.empty());
        when(accessPointRepository.findByFloorId(id)).thenReturn(List.of(AccessPoint.builder().build()));
        FloorService service = new FloorService(buildingRepository, floorRepository, accessPointRepository);

        assertThrows(RuntimeException.class, () -> service.addFloor(id, 1, "First"));
        assertThrows(RuntimeException.class, () -> service.updateFloor(id, 1, "First", "plan.png"));
        assertThrows(RuntimeException.class, () -> service.deleteFloor(id));
    }
}
