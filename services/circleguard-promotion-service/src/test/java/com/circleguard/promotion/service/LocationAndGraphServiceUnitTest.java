package com.circleguard.promotion.service;

import com.circleguard.promotion.model.AccessPoint;
import com.circleguard.promotion.model.Building;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.repository.graph.UserNodeRepository;
import com.circleguard.promotion.repository.jpa.AccessPointRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocationAndGraphServiceUnitTest {

    @Test
    void shouldResolveKnownSignalAndUpdateGraphState() {
        AccessPointRepository accessPointRepository = mock(AccessPointRepository.class);
        MacSessionRegistry sessionRegistry = mock(MacSessionRegistry.class);
        GraphService graphService = mock(GraphService.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        UUID buildingId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID apId = UUID.randomUUID();
        Building building = Building.builder().id(buildingId).build();
        Floor floor = Floor.builder().id(floorId).building(building).floorNumber(3).build();
        AccessPoint ap = AccessPoint.builder()
                .id(apId).floor(floor).macAddress("ap").coordinateX(1.0).coordinateY(2.0).name("AP")
                .build();
        when(accessPointRepository.findByMacAddress("ap")).thenReturn(Optional.of(ap));
        when(sessionRegistry.getAnonymousId("device")).thenReturn("u1");
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("spatial:location:" + apId)).thenReturn(Set.of("u2", "u1"));

        new LocationResolutionService(accessPointRepository, sessionRegistry, graphService, kafkaTemplate, redisTemplate)
                .processSignal("ap", "device", -42.0);

        verify(kafkaTemplate).send(eq("proximity.detected"), eq("u1"), any(Map.class));
        verify(graphService).recordEncounter("u1", "u2", apId.toString());
        verify(setOperations).add("spatial:location:" + apId, "u1");
        verify(redisTemplate).expire("spatial:location:" + apId, Duration.ofMinutes(10));
        verify(graphService).detectAndFormCircles(apId.toString());
    }

    @Test
    void shouldIgnoreUnknownAccessPointAndUnmappedMac() {
        AccessPointRepository accessPointRepository = mock(AccessPointRepository.class);
        MacSessionRegistry sessionRegistry = mock(MacSessionRegistry.class);
        GraphService graphService = mock(GraphService.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AccessPoint ap = AccessPoint.builder()
                .id(UUID.randomUUID())
                .floor(Floor.builder().building(Building.builder().id(UUID.randomUUID()).build()).id(UUID.randomUUID()).build())
                .build();
        when(accessPointRepository.findByMacAddress("unknown")).thenReturn(Optional.empty());
        when(accessPointRepository.findByMacAddress("known")).thenReturn(Optional.of(ap));
        when(sessionRegistry.getAnonymousId("device")).thenReturn(null);
        LocationResolutionService service =
                new LocationResolutionService(accessPointRepository, sessionRegistry, graphService, kafkaTemplate, redisTemplate);

        service.processSignal("unknown", "device", -42.0);
        service.processSignal("known", "device", -42.0);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(graphService, never()).recordEncounter(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRecordEncounterAndRunCircleDetectionQuery() {
        UserNodeRepository userRepository = mock(UserNodeRepository.class);
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        Neo4jClient.UnboundRunnableSpec spec = mock(Neo4jClient.UnboundRunnableSpec.class);
        Neo4jClient.OngoingBindSpec<String, Neo4jClient.RunnableSpec> bindSpec = mock(Neo4jClient.OngoingBindSpec.class);
        when(neo4jClient.query(anyString())).thenReturn(spec);
        when(spec.bind("room-1")).thenReturn(bindSpec);
        when(bindSpec.to("loc")).thenReturn(spec);
        GraphService service = new GraphService(userRepository, neo4jClient);

        service.recordEncounter("u1", "u2", "room-1");
        service.detectAndFormCircles("room-1");

        verify(userRepository).recordEncounter(eq("u1"), eq("u2"), anyLong(), eq("room-1"));
        verify(spec).run();
    }
}
