package com.circleguard.promotion.service;

import com.circleguard.promotion.model.AccessPoint;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.repository.jpa.AccessPointRepository;
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

class AccessPointServiceUnitTest {

    private final AccessPointRepository accessPointRepository = mock(AccessPointRepository.class);
    private final FloorRepository floorRepository = mock(FloorRepository.class);
    private final AccessPointService service = new AccessPointService(accessPointRepository, floorRepository);

    @Test
    void shouldRegisterAccessPoint() {
        UUID floorId = UUID.randomUUID();
        Floor floor = Floor.builder().id(floorId).build();
        when(floorRepository.findById(floorId)).thenReturn(Optional.of(floor));
        when(accessPointRepository.save(any(AccessPoint.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccessPoint ap = service.registerAccessPoint(floorId, "aa:bb", 1.0, 2.0, "AP");

        assertEquals(floor, ap.getFloor());
        assertEquals("aa:bb", ap.getMacAddress());
    }

    @Test
    void shouldReadUpdateAndDeleteAccessPoint() {
        UUID id = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        AccessPoint ap = AccessPoint.builder().id(id).macAddress("old").build();
        when(accessPointRepository.findById(id)).thenReturn(Optional.of(ap));
        when(accessPointRepository.findByFloorId(floorId)).thenReturn(List.of(ap));
        when(accessPointRepository.save(ap)).thenReturn(ap);

        assertEquals(Optional.of(ap), service.getAccessPoint(id));
        assertEquals(List.of(ap), service.getAccessPointsByFloor(floorId));
        AccessPoint updated = service.updateAccessPoint(id, "new", 5.0, 6.0, "Lab");
        service.deleteAccessPoint(id);

        assertEquals("new", updated.getMacAddress());
        assertEquals(5.0, updated.getCoordinateX());
        verify(accessPointRepository).deleteById(id);
    }

    @Test
    void shouldRejectMissingFloorAndMissingAccessPoint() {
        UUID id = UUID.randomUUID();
        when(floorRepository.findById(id)).thenReturn(Optional.empty());
        when(accessPointRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class,
                () -> service.registerAccessPoint(id, "aa:bb", 1.0, 2.0, "AP"));
        assertThrows(RuntimeException.class,
                () -> service.updateAccessPoint(id, "aa:bb", 1.0, 2.0, "AP"));
    }
}
