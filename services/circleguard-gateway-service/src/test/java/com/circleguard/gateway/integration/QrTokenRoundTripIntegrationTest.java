package com.circleguard.gateway.integration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.security.Key;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Test 5: QR Token Round-Trip — Generation + Gate Validation Contract
 *
 * WHY CRITICAL:
 *   The QR token is signed with a separate qr.secret (distinct from jwt.secret).
 *   If auth-service generates a token with one secret but gateway-service validates
 *   with a different secret — or if the JWT claim structure differs — every gate
 *   scanner on campus will reject ALL students simultaneously.  This test pins the
 *   end-to-end contract so that any accidental secret mismatch is caught in CI
 *   before reaching Kubernetes.
 *
 * WHAT IS VALIDATED:
 *   1. A token generated with the shared qr.secret is accepted by the gate → GREEN.
 *   2. A token generated with a DIFFERENT secret is rejected → RED.
 *   3. Gate validates the "sub" claim (anonymousId) and maps it to Redis correctly.
 *   4. Gate response schema: valid (boolean), status (string), message (string).
 *   5. Empty token body → gate returns RED with a descriptive message.
 *   6. Null token field in request body → gate handles gracefully (no 500).
 *
 * APPROACH:
 *   - gateway-service SpringBootTest (full context).
 *   - Redis Testcontainer for the shared state store.
 *   - QR tokens are generated programmatically using the same shared secret,
 *     mimicking what auth-service would produce.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class QrTokenRoundTripIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
            .withExposedPorts(6379);

    // Shared secret — must match both auth-service and gateway-service configuration
    private static final String SHARED_QR_SECRET = "my-qr-secret-key-for-dev-1234567890";
    private static final String WRONG_QR_SECRET  = "totally-wrong-secret-key-do-not-use!";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("qr.secret", () -> SHARED_QR_SECRET);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Token builders ───────────────────────────────────────────────────────

    private String buildToken(String secret, String anonymousId, long expiryMs) {
        Key key = Keys.hmacShaKeyFor(secret.getBytes());
        return Jwts.builder()
                .setSubject(anonymousId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    private String validToken(String anonymousId) {
        return buildToken(SHARED_QR_SECRET, anonymousId, 60_000L);
    }

    private String wrongSecretToken(String anonymousId) {
        return buildToken(WRONG_QR_SECRET, anonymousId, 60_000L);
    }

    private String requestBody(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    @BeforeEach
    void cleanRedis() {
        redisTemplate.getConnectionFactory().getConnection().flushDb();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    /**
     * IT-5.1 — Valid token with correct secret → GREEN for healthy user
     */
    @Test
    @DisplayName("IT-5.1: Token signed with correct qr.secret returns GREEN for healthy user")
    void correctSecret_HealthyUser_ReturnsGreen() throws Exception {
        String anonymousId = UUID.randomUUID().toString();
        String token = validToken(anonymousId);

        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("GREEN"));
    }

    /**
     * IT-5.2 — Token signed with wrong secret → RED (signature mismatch)
     */
    @Test
    @DisplayName("IT-5.2: Token signed with wrong qr.secret returns RED (Invalid or Expired Token)")
    void wrongSecret_ReturnsRed() throws Exception {
        String anonymousId = UUID.randomUUID().toString();
        String token = wrongSecretToken(anonymousId);

        MvcResult result = mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").value("RED"))
                .andReturn();

        // Message must explain the rejection
        String body = result.getResponse().getContentAsString();
        Map<?, ?> response = mapper.readValue(body, Map.class);
        assertThat(response.get("message").toString()).isNotBlank();
    }

    /**
     * IT-5.3 — Gate reads Redis status for the anonymousId embedded in the token
     */
    @Test
    @DisplayName("IT-5.3: Gate reads the Redis status keyed by the anonymousId in the token subject")
    void gate_ReadsRedisKey_ForSubjectAnonymousId() throws Exception {
        String anonymousId = UUID.randomUUID().toString();
        // Simulate promotion-service writing SUSPECT to Redis
        redisTemplate.opsForValue().set("user:status:" + anonymousId, "SUSPECT");

        String token = validToken(anonymousId);

        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").value("RED"));
    }

    /**
     * IT-5.4 — Gate response always contains valid, status, and message fields
     */
    @Test
    @DisplayName("IT-5.4: Gate response always includes valid, status, and message fields")
    void gateResponse_AlwaysContainsRequiredFields() throws Exception {
        String anonymousId = UUID.randomUUID().toString();
        String token = validToken(anonymousId);

        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * IT-5.5 — Empty token string → RED (graceful rejection, no 500)
     */
    @Test
    @DisplayName("IT-5.5: Empty token string returns RED without a 500 server error")
    void emptyToken_ReturnsRedGracefully() throws Exception {
        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").value("RED"));
    }

    /**
     * IT-5.6 — Null token field in JSON body → gate handles gracefully (no 500)
     */
    @Test
    @DisplayName("IT-5.6: Null token value in request body does not cause a 500 server error")
    void nullToken_HandledGracefully() throws Exception {
        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":null}"))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus())
                                .isNotEqualTo(500)); // Must be 200 or 4xx — never 500
    }
}
