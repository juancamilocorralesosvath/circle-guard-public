package com.circleguard.dashboard.service;

import com.circleguard.dashboard.client.PromotionClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PromotionClient promotionClient = mock(PromotionClient.class);
    private final KAnonymityFilter kAnonymityFilter = mock(KAnonymityFilter.class);
    private final AnalyticsService service = new AnalyticsService(jdbc, promotionClient, kAnonymityFilter);

    @Test
    void shouldReturnCampusSummaryFromPromotionService() {
        Map<String, Object> stats = Map.of("activeCount", 10);
        when(promotionClient.getHealthStats()).thenReturn(stats);

        assertEquals(stats, service.getCampusSummary());
        assertEquals(stats, service.getGlobalHealthStats());
    }

    @Test
    void shouldApplyKAnonymityToDepartmentStats() {
        Map<String, Object> raw = Map.of("department", "engineering", "confirmedCount", 2L);
        Map<String, Object> filtered = Map.of("department", "engineering", "confirmedCount", "<5");
        when(promotionClient.getHealthStatsByDepartment("engineering")).thenReturn(raw);
        when(kAnonymityFilter.apply(raw)).thenReturn(filtered);

        assertEquals(filtered, service.getDepartmentStats("engineering"));
        verify(kAnonymityFilter).apply(raw);
    }

    @Test
    void shouldMaskSmallEntryTrendCounts() {
        UUID locationId = UUID.randomUUID();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new LinkedHashMap<>(Map.of("entry_count", 3L, "hour", Instant.now())));
        rows.add(new LinkedHashMap<>(Map.of("entry_count", 8L, "hour", Instant.now())));
        when(jdbc.queryForList(anyString(), any(UUID.class))).thenReturn(rows);

        List<Map<String, Object>> result = service.getEntryTrends(locationId);

        assertEquals("<5", result.get(0).get("entry_count"));
        assertEquals("Insufficient data for privacy", result.get(0).get("note"));
        assertEquals(8L, result.get(1).get("entry_count"));
    }

    @Test
    void shouldReturnTimeSeriesFromDatabase() {
        List<Map<String, Object>> rows = List.of(Map.of("status", "ACTIVE", "total", 42L));
        when(jdbc.queryForList(anyString(), anyInt())).thenReturn(rows);

        assertEquals(rows, service.getTimeSeries("daily", 10));
    }

    @Test
    void shouldGenerateMockTimeSeriesWhenDatabaseIsUnavailable() {
        when(jdbc.queryForList(anyString(), anyInt())).thenThrow(new RuntimeException(new SQLException("missing table")));

        List<Map<String, Object>> result = service.getTimeSeries("hourly", 2);

        assertEquals(8, result.size());
        assertTrue(result.stream().allMatch(point -> point.containsKey("bucket")));
        assertFalse(result.stream().anyMatch(Map::isEmpty));
    }
}
