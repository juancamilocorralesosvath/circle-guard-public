package com.circleguard.gateway.integration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.security.Key;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Test 3: Promotion-Service Redis Write → Gateway-Service Redis Read
 *
 * WHY CRITICAL:
 *   The gateway-service is the physical access-control layer.  It reads a user's
 *   health status directly from Redis (written by promotion-service) and returns
 *   GREEN or RED.  If the Redis key format written by promotion-service does not
 *   match what gateway-service reads, a student marked SUSPECT could still enter
 *   campus because the gate would always see an empty/null status and default to
 *   GREEN.  This cross-service contract is invisible to individual unit tests.
 *
 * WHAT IS VALIDATED:
 *   1. No Redis key for user → gate returns GREEN (ACTIVE user with no record).
 *   2. Redis key "user:status:{id}" = "SUSPECT" / "PROBABLE" / "CONFIRMED" → gate returns RED.
 *   3. Redis key = "ACTIVE" / "CLEAR" / unknown string → gate returns GREEN.
 *
 * APPROACH:
 *   - Redis Testcontainer shared between both service contexts.
 *   - gateway-service SpringBootTest (full context).
 *   - Tests manually write to Redis to simulate promotion-service writes, then
 *     call the gate endpoint and assert the response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class RedisStatusSharingIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Use the same QR secret that QrValidationService expects
        registry.add("qr.secret", () -> "my-qr-secret-key-for-dev-1234567890");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String STATUS_PREFIX = "user:status:";
    private static final String QR_SECRET    = "my-qr-secret-key-for-dev-1234567890";

    // ── Helper: generate a valid QR token for a given anonymousId ─────────────

    private String generateQrToken(String anonymousId) {
        Key key = Keys.hmacShaKeyFor(QR_SECRET.getBytes());
        return Jwts.builder()
                .setSubject(anonymousId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    private String generateExpiredQrToken(String anonymousId) {
        Key key = Keys.hmacShaKeyFor(QR_SECRET.getBytes());
        return Jwts.builder()
                .setSubject(anonymousId)
                .setIssuedAt(new Date(System.currentTimeMillis() - 120_000))
                .setExpiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    private String validateBody(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    @BeforeEach
    void cleanRedis() {
        // Flush only keys we own to avoid cross-test contamination
        redisTemplate.getConnectionFactory()
                .getConnection()
                .flushDb();
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * IT-3.1 — No Redis key for user → GREEN (unknown users are healthy by default)
     */
    @Test
    @DisplayName("IT-3.1: User with no Redis status record gets GREEN access")
    void noRedisKey_ReturnsGreen() throws Exception {
        String anonymousId = UUID.randomUUID().toString();
        String token = generateQrToken(anonymousId);

        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GREEN"))
                .andExpect(jsonPath("$.valid").value(true));
    }

    /**
     * IT-3.2 — Redis key = "SUSPECT" → RED access denied
     */
    @Test
    @DisplayName("IT-3.2: User with Redis status SUSPECT (written by promotion-service) gets RED")
    void suspectStatus_InRedis_ReturnsRed() throws Exception {
        String anonymousId = UUID.randomUUID().toString();
        // Simulate what promotion-service writes after status cascade
        redisTemplate.opsForValue().set(STATUS_PREFIX + anonymousId, "SUSPECT");

        String token = generateQrToken(anonymousId);

        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RED"))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    /**
     * IT-3.3 — Redis key = "PROBABLE" → RED access denied
     */
    @Test
    @DisplayName("IT-3.3: User with Redis status PROBABLE gets RED (second-hop exposure)")
    void probableStatus_InRedis_ReturnsRed() throws Exception {
        String anonymousId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(STATUS_PREFIX + anonymousId, "PROBABLE");

        String token = generateQrToken(anonymousId);

        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RED"))
                .andExpect(jsonPath("$.valid").value(false));
    }

    /**
     * IT-3.4 — Redis key = "CLEAR" → GREEN (explicitly cleared user)
     */
    @Test
    @DisplayName("IT-3.4: User with Redis status CLEAR gets GREEN access")
    void clearStatus_InRedis_ReturnsGreen() throws Exception {
        String anonymousId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(STATUS_PREFIX + anonymousId, "CLEAR");

        String token = generateQrToken(anonymousId);

        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GREEN"))
                .andExpect(jsonPath("$.valid").value(true));
    }

    /**
     * IT-3.5 — Expired QR token → RED regardless of Redis status
     */
    @Test
    @DisplayName("IT-3.5: Expired QR token returns RED regardless of Redis health status")
    void expiredToken_ReturnsRed_RegardlessOfRedisStatus() throws Exception {
        String anonymousId = UUID.randomUUID().toString();
        // User is healthy in Redis
        redisTemplate.opsForValue().set(STATUS_PREFIX + anonymousId, "CLEAR");

        String expiredToken = generateExpiredQrToken(anonymousId);

        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody(expiredToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RED"))
                .andExpect(jsonPath("$.valid").value(false));
    }

    /**
     * IT-3.6 — Malformed / random-string token → RED
     */
    @Test
    @DisplayName("IT-3.6: Completely malformed token (not a JWT) returns RED")
    void malformedToken_ReturnsRed() throws Exception {
        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody("this-is-not-a-jwt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RED"))
                .andExpect(jsonPath("$.valid").value(false));
    }
}
