package com.circleguard.promotion.service;

import com.circleguard.promotion.model.Building;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.repository.jpa.BuildingRepository;
import com.circleguard.promotion.repository.jpa.FloorRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuildingServiceUnitTest {

    private final BuildingRepository buildingRepository = mock(BuildingRepository.class);
    private final FloorRepository floorRepository = mock(FloorRepository.class);
    private final BuildingService service = new BuildingService(buildingRepository, floorRepository);

    @Test
    void shouldCreateAndListBuildings() {
        when(buildingRepository.save(any(Building.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Building created = service.createBuilding("Library", "LIB", "Main", 1.0, 2.0, "Campus");
        when(buildingRepository.findAll()).thenReturn(List.of(created));

        assertEquals("Library", created.getName());
        assertEquals("LIB", created.getCode());
        assertEquals(List.of(created), service.getAllBuildings());
    }

    @Test
    void shouldUpdateExistingBuilding() {
        UUID id = UUID.randomUUID();
        Building building = Building.builder().id(id).name("Old").build();
        when(buildingRepository.findById(id)).thenReturn(Optional.of(building));
        when(buildingRepository.save(building)).thenReturn(building);

        Building updated = service.updateBuilding(id, "New", "NEW", "Updated", 3.0, 4.0, "North");

        assertEquals("New", updated.getName());
        assertEquals("NEW", updated.getCode());
        assertEquals("North", updated.getAddress());
    }

    @Test
    void shouldRejectMissingBuildingUpdateAndDeleteWithFloors() {
        UUID id = UUID.randomUUID();
        when(buildingRepository.findById(id)).thenReturn(Optional.empty());
        when(floorRepository.findByBuildingId(id)).thenReturn(List.of(Floor.builder().build()));

        assertThrows(RuntimeException.class,
                () -> service.updateBuilding(id, "New", "NEW", "Updated", 3.0, 4.0, "North"));
        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.deleteBuilding(id));
        assertTrue(exception.getMessage().contains("existing floors"));
    }

    @Test
    void shouldDeleteBuildingWithoutFloors() {
        UUID id = UUID.randomUUID();
        when(floorRepository.findByBuildingId(id)).thenReturn(List.of());

        service.deleteBuilding(id);

        verify(buildingRepository).deleteById(id);
    }
}
