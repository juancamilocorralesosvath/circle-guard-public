package com.circleguard.promotion.controller;

import com.circleguard.promotion.dto.BuildingDTO;
import com.circleguard.promotion.dto.FloorDTO;
import com.circleguard.promotion.model.Building;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.service.BuildingService;
import com.circleguard.promotion.service.FloorService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuildingControllerUnitTest {

    private final BuildingService buildingService = mock(BuildingService.class);
    private final FloorService floorService = mock(FloorService.class);
    private final BuildingController controller = new BuildingController(buildingService, floorService);

    @Test
    void shouldCreateListUpdateDeleteAndReturnFloors() {
        UUID buildingId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        Building building = Building.builder()
                .id(buildingId).name("Library").code("LIB").description("Main")
                .latitude(1.0).longitude(2.0).address("Campus")
                .build();
        Floor floor = Floor.builder().id(floorId).building(building).floorNumber(1).name("First").build();
        building.setFloors(List.of(floor));
        BuildingDTO request = BuildingDTO.builder()
                .name("Library").code("LIB").description("Main")
                .latitude(1.0).longitude(2.0).address("Campus")
                .build();
        when(buildingService.createBuilding("Library", "LIB", "Main", 1.0, 2.0, "Campus")).thenReturn(building);
        when(buildingService.getAllBuildings()).thenReturn(List.of(building));
        when(buildingService.updateBuilding(buildingId, "Library", "LIB", "Main", 1.0, 2.0, "Campus")).thenReturn(building);
        when(floorService.getFloorsByBuilding(buildingId)).thenReturn(List.of(floor));
        when(floorService.addFloor(buildingId, 1, "First")).thenReturn(floor);

        assertEquals("Library", controller.createBuilding(request).getBody().getName());
        assertEquals(1, controller.listBuildings().getBody().size());
        assertEquals("First", controller.getFloors(buildingId).getBody().get(0).getName());
        assertEquals("First", controller.addFloor(buildingId, FloorDTO.builder().floorNumber(1).name("First").build()).getBody().getName());
        assertEquals("LIB", controller.updateBuilding(buildingId, request).getBody().getCode());
        assertEquals(200, controller.deleteBuilding(buildingId).getStatusCode().value());
        verify(buildingService).deleteBuilding(buildingId);
    }
}
