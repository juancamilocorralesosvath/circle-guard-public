package com.circleguard.dashboard.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Test 3: KAnonymityFilter – FERPA-Compliant Privacy Masking
 *
 * WHY CRITICAL:
 *   The K-Anonymity filter is the last line of defence against accidental
 *   re-identification of individual health records in the analytics dashboard.
 *   A regression here could expose which specific individual in a small
 *   department was marked as CONFIRMED, violating FERPA regulations and
 *   putting the institution at legal risk.
 *
 * WHAT IS VALIDATED:
 *   1. Population below K (< 5): entire result is masked with a privacy note.
 *   2. Population exactly K (= 5): data is visible (boundary condition).
 *   3. Population above K: individual count fields below K are replaced with "<K".
 *   4. Population above K: count fields at or above K are left unchanged.
 *   5. Null input map: returns empty map without NullPointerException.
 *   6. Custom K value (K=10): masking applies to the custom threshold correctly.
 *   7. Zero total users: does not trigger masking (edge-case protection).
 */
class KAnonymityFilterTest {

    private KAnonymityFilter filter;

    @BeforeEach
    void setUp() {
        filter = new KAnonymityFilter();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3.1 — Population below default K=5 → full masking
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Department with totalUsers=3 (below K=5) gets fully masked response")
    void belowK_TotalPopulation_ReturnsFullyMaskedResult() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("department", "School of Education");
        stats.put("activeCount", 2L);
        stats.put("confirmedCount", 1L);
        stats.put("totalUsers", 3L);

        Map<String, Object> result = filter.apply(stats);

        assertTrue(result.containsKey("note"),
                "Result must contain 'note' key for below-K population");
        assertEquals("Insufficient data for privacy", result.get("note"),
                "Privacy note text must match expected message");
        assertFalse(result.containsKey("confirmedCount"),
                "confirmedCount must NOT be present when population is below K");
        assertFalse(result.containsKey("activeCount"),
                "activeCount must NOT be present when population is below K");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3.2 — Population exactly K=5 → data visible (inclusive boundary)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Department with totalUsers=5 (exactly K=5) returns visible data")
    void exactlyK_TotalPopulation_DataIsVisible() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("department", "Faculty of Natural Sciences");
        stats.put("activeCount", 5L);
        stats.put("confirmedCount", 0L);
        stats.put("totalUsers", 5L);

        Map<String, Object> result = filter.apply(stats);

        assertFalse(result.containsKey("note"),
                "No privacy note should appear when totalUsers == K");
        assertTrue(result.containsKey("totalUsers"),
                "totalUsers must be present when population equals K");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3.3 — Individual count below K is masked
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Individual count field of 2 (below K=5) is replaced with '<5'")
    void individualCountBelowK_IsMasked() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("department", "Faculty of Health Sciences");
        stats.put("activeCount", 100L);   // above K
        stats.put("confirmedCount", 2L);  // below K → must be masked
        stats.put("totalUsers", 150L);

        Map<String, Object> result = filter.apply(stats);

        assertEquals("<5", result.get("confirmedCount"),
                "confirmedCount=2 (below K=5) must be replaced with '<5'");
        assertEquals(100L, result.get("activeCount"),
                "activeCount=100 (above K=5) must remain unchanged");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3.4 — Count equal to K is NOT masked
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Individual count field of exactly K=5 is NOT masked")
    void individualCountEqualK_IsNotMasked() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("department", "Faculty of Law");
        stats.put("suspectCount", 5L);   // exactly K → should NOT be masked
        stats.put("totalUsers", 200L);

        Map<String, Object> result = filter.apply(stats);

        assertEquals(5L, result.get("suspectCount"),
                "suspectCount=5 (equal to K=5) must NOT be masked");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3.5 — Null input → safe empty map (no NPE)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Null input map returns empty map without NullPointerException")
    void nullInput_ReturnsSafeEmptyMap() {
        assertDoesNotThrow(() -> filter.apply(null),
                "apply(null) must not throw");
        Map<String, Object> result = filter.apply(null);
        assertNotNull(result, "Result must not be null for null input");
        assertTrue(result.isEmpty() || result.containsKey("note"),
                "Result for null input must be empty or a masked response");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3.6 — Custom K value (K=10) applies correctly
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Custom K=10: population of 7 is fully masked; population of 11 is visible")
    void customK10_MasksBelowTen_AndShowsAboveTen() {
        Map<String, Object> smallDept = new LinkedHashMap<>();
        smallDept.put("totalUsers", 7L);
        smallDept.put("confirmedCount", 1L);

        Map<String, Object> resultSmall = filter.apply(smallDept, 10);
        assertTrue(resultSmall.containsKey("note"),
                "Population of 7 must be masked when K=10");

        Map<String, Object> largeDept = new LinkedHashMap<>();
        largeDept.put("totalUsers", 11L);
        largeDept.put("confirmedCount", 2L); // below K=10 → individual count masked
        largeDept.put("activeCount", 9L);    // below K=10 → individual count masked

        Map<String, Object> resultLarge = filter.apply(largeDept, 10);
        assertFalse(resultLarge.containsKey("note"),
                "Population of 11 must NOT be fully masked when K=10");
        assertEquals("<10", resultLarge.get("confirmedCount"),
                "confirmedCount=2 must be masked as '<10' when K=10");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3.7 — Zero total users does not trigger masking
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("totalUsers=0 does NOT trigger privacy masking (no users to protect)")
    void zeroTotalUsers_DoesNotTriggerMasking() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("department", "Empty Department");
        stats.put("confirmedCount", 0L);
        stats.put("totalUsers", 0L);

        Map<String, Object> result = filter.apply(stats);

        assertFalse(result.containsKey("note"),
                "Zero population should not trigger privacy masking");
    }
}
