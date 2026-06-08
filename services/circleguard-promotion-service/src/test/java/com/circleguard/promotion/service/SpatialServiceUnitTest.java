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

class SpatialServiceUnitTest {

    private final BuildingRepository buildingRepository = mock(BuildingRepository.class);
    private final FloorRepository floorRepository = mock(FloorRepository.class);
    private final AccessPointRepository accessPointRepository = mock(AccessPointRepository.class);
    private final SpatialService service = new SpatialService(buildingRepository, floorRepository, accessPointRepository);

    @Test
    void shouldManageBuildingsFloorsAndAccessPoints() {
        UUID buildingId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID apId = UUID.randomUUID();
        Building building = Building.builder().id(buildingId).name("Library").build();
        Floor floor = Floor.builder().id(floorId).building(building).floorNumber(1).build();
        AccessPoint ap = AccessPoint.builder().id(apId).floor(floor).macAddress("aa:bb").build();
        when(buildingRepository.save(any(Building.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(floorRepository.save(any(Floor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accessPointRepository.save(any(AccessPoint.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(buildingRepository.findById(buildingId)).thenReturn(Optional.of(building));
        when(floorRepository.findById(floorId)).thenReturn(Optional.of(floor));
        when(accessPointRepository.findById(apId)).thenReturn(Optional.of(ap));
        when(buildingRepository.findAll()).thenReturn(List.of(building));
        when(floorRepository.findByBuildingId(buildingId)).thenReturn(List.of(floor), List.of());
        when(accessPointRepository.findByFloorId(floorId)).thenReturn(List.of(ap), List.of());

        assertEquals("Library", service.createBuilding("Library", "LIB", "Main").getName());
        assertEquals(building, service.addFloor(buildingId, 1, "First").getBuilding());
        assertEquals(List.of(building), service.getAllBuildings());
        assertEquals(List.of(floor), service.getFloorsByBuilding(buildingId));
        assertEquals(building, service.updateBuilding(buildingId, "New", "NEW", "Updated"));
        assertEquals(floor, service.updateFloor(floorId, 2, "Second"));
        assertEquals(floor, service.registerAccessPoint(floorId, "aa:bb", 1.0, 2.0, "AP").getFloor());
        assertEquals(Optional.of(ap), service.getAccessPoint(apId));
        assertEquals(List.of(ap), service.getAccessPointsByFloor(floorId));
        assertEquals(ap, service.updateAccessPoint(apId, "cc:dd", 3.0, 4.0, "Lab"));

        service.deleteBuilding(buildingId);
        service.deleteFloor(floorId);
        service.deleteAccessPoint(apId);

        verify(buildingRepository).deleteById(buildingId);
        verify(floorRepository).deleteById(floorId);
        verify(accessPointRepository).deleteById(apId);
    }

    @Test
    void shouldRejectDeletesWithChildrenAndMissingEntities() {
        UUID id = UUID.randomUUID();
        when(buildingRepository.findById(id)).thenReturn(Optional.empty());
        when(floorRepository.findById(id)).thenReturn(Optional.empty());
        when(accessPointRepository.findById(id)).thenReturn(Optional.empty());
        when(floorRepository.findByBuildingId(id)).thenReturn(List.of(Floor.builder().build()));
        when(accessPointRepository.findByFloorId(id)).thenReturn(List.of(AccessPoint.builder().build()));

        assertThrows(RuntimeException.class, () -> service.addFloor(id, 1, "First"));
        assertThrows(RuntimeException.class, () -> service.updateBuilding(id, "New", "NEW", "Updated"));
        assertThrows(RuntimeException.class, () -> service.updateFloor(id, 1, "First"));
        assertThrows(RuntimeException.class, () -> service.registerAccessPoint(id, "aa:bb", 1.0, 2.0, "AP"));
        assertThrows(RuntimeException.class, () -> service.updateAccessPoint(id, "aa:bb", 1.0, 2.0, "AP"));
        assertThrows(RuntimeException.class, () -> service.deleteBuilding(id));
        assertThrows(RuntimeException.class, () -> service.deleteFloor(id));
    }
}
