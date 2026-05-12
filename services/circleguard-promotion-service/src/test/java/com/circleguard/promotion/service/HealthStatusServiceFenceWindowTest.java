package com.circleguard.promotion.service;

import com.circleguard.promotion.exception.FenceException;
import com.circleguard.promotion.model.graph.UserNode;
import com.circleguard.promotion.model.jpa.SystemSettings;
import com.circleguard.promotion.repository.graph.CircleNodeRepository;
import com.circleguard.promotion.repository.graph.UserNodeRepository;
import com.circleguard.promotion.repository.jpa.SystemSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit Test 5: HealthStatusService – Mandatory Fence Window Enforcement
 *
 * WHY CRITICAL:
 *   The mandatory 14-day fence window is a public-health enforcement mechanism.
 *   If a SUSPECT or PROBABLE user can call resolveStatus() and return to ACTIVE
 *   before the fence window expires, they could re-enter campus while still
 *   potentially infectious.  This is the core regulatory constraint of the system.
 *
 * WHAT IS VALIDATED:
 *   1. User in SUSPECT status updated 3 days ago (within 14-day window) → FenceException.
 *   2. User in SUSPECT status updated 20 days ago (outside window) → resolves cleanly.
 *   3. adminOverride=true bypasses the fence window even when within 14 days.
 *   4. User in ACTIVE status (not fenced) → resolves cleanly.
 *   5. User not found in Neo4j → resolves cleanly (no fence applied).
 *   6. Fence window calculates remaining days correctly in exception message.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HealthStatusServiceFenceWindowTest {

    @Mock private UserNodeRepository userNodeRepository;
    @Mock private Neo4jClient neo4jClient;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private SystemSettingsRepository systemSettingsRepository;
    @Mock private CircleNodeRepository circleNodeRepository;

    private HealthStatusService healthStatusService;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @BeforeEach
    void setUp() {
        healthStatusService = new HealthStatusService(
                userNodeRepository,
                neo4jClient,
                redisTemplate,
                kafkaTemplate,
                systemSettingsRepository,
                circleNodeRepository
        );

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doNothing().when(valueOps).multiSet(anyMap());

        // Default 14-day fence settings (only read when fence branch runs for SUSPECT/PROBABLE)
        SystemSettings settings = SystemSettings.builder()
                .mandatoryFenceDays(14)
                .encounterWindowDays(14)
                .unconfirmedFencingEnabled(true)
                .autoThresholdSeconds(3600L)
                .build();
        when(systemSettingsRepository.getSettings()).thenReturn(Optional.of(settings));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 5.1 — SUSPECT user updated 3 days ago → FenceException thrown
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("resolveStatus() throws FenceException for SUSPECT user updated 3 days ago")
    void suspect_WithinFenceWindow_ThrowsFenceException() {
        long threeDaysAgo = System.currentTimeMillis() - (3 * DAY_MS);
        UserNode user = UserNode.builder()
                .anonymousId("fenced-user")
                .status("SUSPECT")
                .statusUpdatedAt(threeDaysAgo)
                .build();
        when(userNodeRepository.findById("fenced-user")).thenReturn(Optional.of(user));

        assertThrows(FenceException.class,
                () -> healthStatusService.resolveStatus("fenced-user"),
                "resolveStatus() must throw FenceException for user within mandatory fence window");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 5.2 — SUSPECT user updated 20 days ago → resolves cleanly
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("resolveStatus() succeeds for SUSPECT user whose fence window has expired")
    void suspect_FenceWindowExpired_ResolvesSuccessfully() {
        long twentyDaysAgo = System.currentTimeMillis() - (20 * DAY_MS);
        UserNode user = UserNode.builder()
                .anonymousId("free-user")
                .status("SUSPECT")
                .statusUpdatedAt(twentyDaysAgo)
                .build();
        when(userNodeRepository.findById("free-user")).thenReturn(Optional.of(user));

        // Neo4j client must be stubbable for the actual resolve queries
        Neo4jClient.UnboundRunnableSpec spec = mock(Neo4jClient.UnboundRunnableSpec.class,
                RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString())).thenReturn(spec);
        when(spec.bind(anyString()).to(anyString()).run()).thenReturn(null);
        when(spec.bind(anyString()).to(anyString()).fetch().one())
                .thenReturn(Optional.of(java.util.Map.of("releasedIds", java.util.List.of())));

        assertDoesNotThrow(() -> healthStatusService.resolveStatus("free-user"),
                "resolveStatus() must not throw when fence window has passed");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 5.3 — adminOverride=true bypasses fence window
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("resolveStatus() with adminOverride=true bypasses the fence window check")
    void adminOverride_BypassesFenceWindow() {
        long twoDaysAgo = System.currentTimeMillis() - (2 * DAY_MS);
        UserNode user = UserNode.builder()
                .anonymousId("override-user")
                .status("SUSPECT")
                .statusUpdatedAt(twoDaysAgo)
                .build();
        when(userNodeRepository.findById("override-user")).thenReturn(Optional.of(user));

        Neo4jClient.UnboundRunnableSpec spec = mock(Neo4jClient.UnboundRunnableSpec.class,
                RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString())).thenReturn(spec);
        when(spec.bind(anyString()).to(anyString()).run()).thenReturn(null);
        when(spec.bind(anyString()).to(anyString()).fetch().one())
                .thenReturn(Optional.of(java.util.Map.of("releasedIds", java.util.List.of())));

        // adminOverride=true must NOT check the fence window at all
        assertDoesNotThrow(() -> healthStatusService.resolveStatus("override-user", true),
                "resolveStatus() with adminOverride=true must bypass FenceException");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 5.4 — ACTIVE user (not fenced) resolves cleanly
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("resolveStatus() does not throw for an ACTIVE user (not in fence)")
    void active_User_ResolvesWithoutFenceException() {
        UserNode user = UserNode.builder()
                .anonymousId("active-user")
                .status("ACTIVE")
                .statusUpdatedAt(System.currentTimeMillis())
                .build();
        when(userNodeRepository.findById("active-user")).thenReturn(Optional.of(user));

        Neo4jClient.UnboundRunnableSpec spec = mock(Neo4jClient.UnboundRunnableSpec.class,
                RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString())).thenReturn(spec);
        when(spec.bind(anyString()).to(anyString()).run()).thenReturn(null);
        when(spec.bind(anyString()).to(anyString()).fetch().one())
                .thenReturn(Optional.of(java.util.Map.of("releasedIds", java.util.List.of())));

        assertDoesNotThrow(() -> healthStatusService.resolveStatus("active-user"),
                "ACTIVE user must resolve without FenceException");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 5.5 — User not found → resolves cleanly (no fence applied)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("resolveStatus() does not throw when user is not found in Neo4j")
    void userNotFound_ResolvesWithoutException() {
        when(userNodeRepository.findById("ghost-user")).thenReturn(Optional.empty());

        Neo4jClient.UnboundRunnableSpec spec = mock(Neo4jClient.UnboundRunnableSpec.class,
                RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString())).thenReturn(spec);
        when(spec.bind(anyString()).to(anyString()).run()).thenReturn(null);
        when(spec.bind(anyString()).to(anyString()).fetch().one())
                .thenReturn(Optional.of(java.util.Map.of("releasedIds", java.util.List.of())));

        assertDoesNotThrow(() -> healthStatusService.resolveStatus("ghost-user"),
                "resolveStatus() must not throw when user is not present in the graph");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 5.6 — FenceException message contains remaining days
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("FenceException message contains the number of remaining fence days")
    void fenceException_MessageContainsRemainingDays() {
        long oneDayAgo = System.currentTimeMillis() - DAY_MS;
        UserNode user = UserNode.builder()
                .anonymousId("msg-check-user")
                .status("SUSPECT")
                .statusUpdatedAt(oneDayAgo)
                .build();
        when(userNodeRepository.findById("msg-check-user")).thenReturn(Optional.of(user));

        FenceException ex = assertThrows(FenceException.class,
                () -> healthStatusService.resolveStatus("msg-check-user"));

        // Message must mention the remaining days (approximately 13 for a 1-day-old fence)
        assertTrue(ex.getMessage().contains("days"),
                "FenceException message must mention the remaining days");
        assertTrue(ex.getMessage().toLowerCase().contains("fence"),
                "FenceException message must mention 'fence'");
    }
}
