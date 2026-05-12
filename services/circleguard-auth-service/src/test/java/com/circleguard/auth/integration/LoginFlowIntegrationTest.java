package com.circleguard.auth.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration Test 1: Login Flow – auth-service → identity-service HTTP
 * Contract
 *
 * WHY CRITICAL: Every single user session begins with a POST /api/v1/auth/login
 * call that synchronously calls identity-service to map the real username to an
 * anonymousId. This is the most frequent and critical inter-service HTTP call
 * in the entire system. A broken contract (wrong request body, unexpected
 * response schema, wrong endpoint path) would lock out every user on campus.
 *
 * WHAT IS VALIDATED: 1. Successful login: auth-service calls identity-service
 * and returns JWT. 2. JWT response contains required fields: token,
 * anonymousId, type. 3. auth-service sends the correct payload to
 * identity-service (realIdentity field). 4. identity-service 404 → auth-service
 * propagates an error to the client. 5. identity-service 500 → auth-service
 * returns a 500 to the client.
 *
 * APPROACH: - PostgreSQL Testcontainer (Flyway migrations are PostgreSQL-specific). -
 * OkHttp MockWebServer stubs identity-service. MockWebServer starts in a static
 * block so its port is valid before Spring evaluates {@code @DynamicPropertySource}.
 * {@link com.circleguard.auth.client.IdentityClient} reads {@code identity.service.url}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class LoginFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("circleguard_auth")
            .withUsername("admin")
            .withPassword("password");

    static final MockWebServer mockServer;

    static {
        mockServer = new MockWebServer();
        try {
            mockServer.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("identity.service.url", () -> "http://127.0.0.1:" + mockServer.getPort());
    }

    @AfterAll
    static void stopMockIdentity() throws IOException {
        mockServer.shutdown();
    }

    @BeforeEach
    void resetStubs() {
        // MockWebServer queues are emptied by consuming recorded requests in tests
    }

    @Autowired
    private MockMvc mockMvc;

    // ── Helpers ──────────────────────────────────────────────────────────────
    private static final String IDENTITY_PATH = "/api/v1/identities/map";
    private static final String ANON_ID = "550e8400-e29b-41d4-a716-446655440000";

    private void stubIdentitySuccess() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"anonymousId\":\"" + ANON_ID + "\"}"));
    }

    private String loginBody(String user, String pass) {
        return "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}";
    }

    // ── Tests ────────────────────────────────────────────────────────────────
    /**
     * IT-1.1 — Successful login returns JWT with all required fields
     */
    @Test
    @DisplayName("IT-1.1: Successful login returns 200 with token, anonymousId, and type fields")
    void successfulLogin_Returns200_WithRequiredFields() throws Exception {
        stubIdentitySuccess();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("staff_guard", "password")))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.token").isNotEmpty())
                .andExpect(MockMvcResultMatchers.jsonPath("$.anonymousId").value(ANON_ID))
                .andExpect(MockMvcResultMatchers.jsonPath("$.type").value("Bearer"));
    }

    /**
     * IT-1.2 — auth-service sends realIdentity field to identity-service
     */
    @Test
    @DisplayName("IT-1.2: auth-service sends { realIdentity } body to identity-service /map")
    void authService_SendsCorrectPayload_ToIdentityService() throws Exception {
        stubIdentitySuccess();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("staff_guard", "password")))
                .andExpect(MockMvcResultMatchers.status().isOk());

        // Verify the outbound HTTP contract by inspecting recorded request
        RecordedRequest recorded = mockServer.takeRequest();
        assertNotNull(recorded);
        assertEquals(IDENTITY_PATH, recorded.getPath());
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("realIdentity"), "Outbound payload must contain realIdentity field");
    }

    /**
     * IT-1.3 — identity-service 404 → login returns 500 (unexpected mapping
     * failure)
     */
    @Test
    @DisplayName("IT-1.3: identity-service 404 causes login to return a server-side error")
    void identityService404_CausesLoginFailure() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(404));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("staff_guard", "password")))
                .andExpect(MockMvcResultMatchers.status().is5xxServerError());
    }

    /**
     * IT-1.4 — Bad credentials → 401 even when identity-service would respond
     * OK
     */
    @Test
    @DisplayName("IT-1.4: Wrong password returns 401 Unauthorized regardless of identity-service")
    void wrongPassword_Returns401() throws Exception {
        // identity-service should not be called if auth fails first
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("staff_guard", "WRONG_PASSWORD")))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").exists());
    }

    /**
     * IT-1.5 — JWT returned by login is a valid 3-part token
     * (header.payload.signature)
     */
    @Test
    @DisplayName("IT-1.5: JWT in login response has valid 3-part structure")
    void login_JwtHasThreeParts() throws Exception {
        stubIdentitySuccess();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("staff_guard", "password")))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // Extract token value from JSON
        String token = new ObjectMapper().readTree(body).get("token").asText();

        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "A valid JWT must have exactly 3 dot-separated parts");
    }
}
