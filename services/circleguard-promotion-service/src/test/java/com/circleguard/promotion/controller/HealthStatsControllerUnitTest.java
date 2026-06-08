package com.circleguard.promotion.controller;

import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthStatsControllerUnitTest {

    @Test
    void shouldAggregateCampusStats() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        Neo4jClient.UnboundRunnableSpec spec = mock(Neo4jClient.UnboundRunnableSpec.class);
        Neo4jClient.RecordFetchSpec<Map<String, Object>> fetch = mock(Neo4jClient.RecordFetchSpec.class);
        when(neo4jClient.query(anyString())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        Map<String, Object> unknownRow = new LinkedHashMap<>();
        unknownRow.put("status", null);
        unknownRow.put("total", 1L);
        Collection<Map<String, Object>> rows = List.of(
                Map.of("status", "ACTIVE", "total", 10L),
                Map.of("status", "CONFIRMED", "total", 2L),
                unknownRow
        );
        when(fetch.all()).thenReturn(rows);

        Map<String, Object> result = new HealthStatsController(neo4jClient).getStats().getBody();

        assertEquals(13L, result.get("totalUsers"));
        assertEquals(2L, result.get("confirmedCount"));
        assertEquals(1L, result.get("unknownCount"));
    }

    @Test
    void shouldAggregateDepartmentStats() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        Neo4jClient.UnboundRunnableSpec spec = mock(Neo4jClient.UnboundRunnableSpec.class);
        Neo4jClient.OngoingBindSpec<String, Neo4jClient.RunnableSpec> bindSpec = mock(Neo4jClient.OngoingBindSpec.class);
        Neo4jClient.RecordFetchSpec<Map<String, Object>> fetch = mock(Neo4jClient.RecordFetchSpec.class);
        when(neo4jClient.query(anyString())).thenReturn(spec);
        when(spec.bind("engineering")).thenReturn(bindSpec);
        when(bindSpec.to("dept")).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        Collection<Map<String, Object>> rows = List.of(
                Map.of("status", "ACTIVE", "total", 5L),
                Map.of("status", "SUSPECT", "total", 1L)
        );
        when(fetch.all()).thenReturn(rows);

        Map<String, Object> result =
                new HealthStatsController(neo4jClient).getStatsByDepartment("engineering").getBody();

        assertEquals("engineering", result.get("department"));
        assertEquals(6L, result.get("totalUsers"));
        assertEquals(1L, result.get("suspectCount"));
    }
}
