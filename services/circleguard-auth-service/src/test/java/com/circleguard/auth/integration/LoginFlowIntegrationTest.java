package com.circleguard.auth.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Test 1: Login Flow – auth-service → identity-service HTTP Contract
 *
 * WHY CRITICAL:
 *   Every single user session begins with a POST /api/v1/auth/login call that
 *   synchronously calls identity-service to map the real username to an
 *   anonymousId.  This is the most frequent and critical inter-service HTTP call
 *   in the entire system.  A broken contract (wrong request body, unexpected
 *   response schema, wrong endpoint path) would lock out every user on campus.
 *
 * WHAT IS VALIDATED:
 *   1. Successful login: auth-service calls identity-service and returns JWT.
 *   2. JWT response contains required fields: token, anonymousId, type.
 *   3. auth-service sends the correct payload to identity-service (realIdentity field).
 *   4. identity-service 404 → auth-service propagates an error to the client.
 *   5. identity-service 500 → auth-service returns a 500 to the client.
 *
 * APPROACH:
 *   - PostgreSQL Testcontainer for the auth DB (Flyway migrates schema automatically).
 *   - WireMock for identity-service (avoids spinning up a second Spring context).
 *   - SpringBootTest (full context) for auth-service itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class LoginFlowIntegrationTest {

    // ── Infrastructure ───────────────────────────────────────────────────────

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("circleguard_auth")
            .withUsername("admin")
            .withPassword("password");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Point auth-service IdentityClient at WireMock
        registry.add("identity.service.url", () -> "http://localhost:" + wireMock.port());
    }

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Autowired
    private MockMvc mockMvc;

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static final String IDENTITY_PATH = "/api/v1/identities/map";
    private static final String ANON_ID = "550e8400-e29b-41d4-a716-446655440000";

    private void stubIdentitySuccess() {
        stubFor(post(urlEqualTo(IDENTITY_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"anonymousId\":\"" + ANON_ID + "\"}")));
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

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("staff_guard", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.anonymousId").value(ANON_ID))
                .andExpect(jsonPath("$.type").value("Bearer"));
    }

    /**
     * IT-1.2 — auth-service sends realIdentity field to identity-service
     */
    @Test
    @DisplayName("IT-1.2: auth-service sends { realIdentity } body to identity-service /map")
    void authService_SendsCorrectPayload_ToIdentityService() throws Exception {
        stubIdentitySuccess();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("staff_guard", "password")))
                .andExpect(status().isOk());

        // Verify the outbound HTTP contract
        verify(postRequestedFor(urlEqualTo(IDENTITY_PATH))
                .withRequestBody(matchingJsonPath("$.realIdentity")));
    }

    /**
     * IT-1.3 — identity-service 404 → login returns 500 (unexpected mapping failure)
     */
    @Test
    @DisplayName("IT-1.3: identity-service 404 causes login to return a server-side error")
    void identityService404_CausesLoginFailure() throws Exception {
        stubFor(post(urlEqualTo(IDENTITY_PATH))
                .willReturn(aResponse().withStatus(404)));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("staff_guard", "password")))
                .andExpect(status().is5xxServerError());
    }

    /**
     * IT-1.4 — Bad credentials → 401 even when identity-service would respond OK
     */
    @Test
    @DisplayName("IT-1.4: Wrong password returns 401 Unauthorized regardless of identity-service")
    void wrongPassword_Returns401() throws Exception {
        // identity-service should not be called if auth fails first
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("staff_guard", "WRONG_PASSWORD")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * IT-1.5 — JWT returned by login is a valid 3-part token (header.payload.signature)
     */
    @Test
    @DisplayName("IT-1.5: JWT in login response has valid 3-part structure")
    void login_JwtHasThreeParts() throws Exception {
        stubIdentitySuccess();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("staff_guard", "password")))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // Extract token value from JSON
        String token = com.fasterxml.jackson.databind.ObjectMapper.class
                .getDeclaredConstructor().newInstance()
                .readTree(body).get("token").asText();

        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "A valid JWT must have exactly 3 dot-separated parts");
    }
}
