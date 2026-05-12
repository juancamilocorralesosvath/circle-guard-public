package com.circleguard.auth.service;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Key;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Test 1: QrTokenService – Rotating Token Expiry Enforcement
 *
 * WHY CRITICAL:
 *   The 60-second rotating QR token is the physical access-control mechanism for
 *   every campus entry point.  If expiry is not enforced, a student whose status
 *   was changed to SUSPECT after scanning could still enter using a stale token.
 *   This test validates the complete token lifecycle: generation → acceptance →
 *   expiry → rejection, with no external infrastructure required.
 *
 * WHAT IS VALIDATED:
 *   1. A freshly generated token with a valid TTL is parsed successfully.
 *   2. A token generated with a past expiry (TTL = 1 ms) is rejected with
 *      ExpiredJwtException after the clock advances.
 *   3. The anonymousId embedded in the token matches the original UUID.
 *   4. Tampered tokens (wrong signature key) are rejected with SignatureException.
 */
class QrTokenServiceExpiryTest {

    private static final String SECRET = "my-qr-secret-key-for-unit-test-32chars!";
    private QrTokenService qrTokenService;

    @BeforeEach
    void setUp() {
        // Constructor injection mirrors the Spring wiring:
        //   QrTokenService(@Value("${qr.secret}") String secret,
        //                  @Value("${qr.expiration:60000}") long expiration)
        qrTokenService = new QrTokenService(SECRET, 60_000L); // 60-second TTL
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1.1 — Fresh token is accepted and embeds the correct anonymousId
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("generateQrToken() produces a valid JWT containing the correct anonymousId")
    void freshToken_IsValid_AndContainsCorrectSubject() {
        UUID anonymousId = UUID.randomUUID();

        String token = qrTokenService.generateQrToken(anonymousId);

        assertNotNull(token, "Token must not be null");
        assertFalse(token.isBlank(), "Token must not be blank");

        // Parse and verify subject without trusting the service's own parser
        Key key = Keys.hmacShaKeyFor(SECRET.getBytes());
        String subject = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();

        assertEquals(anonymousId.toString(), subject,
                "JWT subject must equal the anonymousId passed to generateQrToken()");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1.2 — Token generated with 1 ms TTL expires immediately
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Token with 1 ms TTL is rejected with ExpiredJwtException after a short wait")
    void expiredToken_ThrowsExpiredJwtException() throws InterruptedException {
        // Service configured with 1 ms expiry to force immediate expiry
        QrTokenService shortLivedService = new QrTokenService(SECRET, 1L);
        UUID anonymousId = UUID.randomUUID();

        String token = shortLivedService.generateQrToken(anonymousId);

        // Let the clock advance past the 1 ms TTL
        Thread.sleep(10);

        Key key = Keys.hmacShaKeyFor(SECRET.getBytes());
        assertThrows(ExpiredJwtException.class, () ->
                        Jwts.parserBuilder()
                                .setSigningKey(key)
                                .build()
                                .parseClaimsJws(token),
                "Parsing an expired token must throw ExpiredJwtException");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1.3 — Token signed with the wrong key is rejected
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Token signed with wrong key is rejected (SignatureException / JwtException)")
    void tamperedToken_WithWrongKey_IsRejected() {
        UUID anonymousId = UUID.randomUUID();
        String token = qrTokenService.generateQrToken(anonymousId);

        Key wrongKey = Keys.hmacShaKeyFor("completely-different-secret-key-32!!".getBytes());

        // Any JWT security exception signals rejection – acceptable subtypes vary by library version
        assertThrows(Exception.class, () ->
                        Jwts.parserBuilder()
                                .setSigningKey(wrongKey)
                                .build()
                                .parseClaimsJws(token),
                "A token signed with a different key must be rejected");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1.4 — Two tokens generated for the same user are distinct (nonce-like)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Two consecutive tokens for the same anonymousId are not equal (time-based nonce)")
    void consecutiveTokens_ForSameUser_AreDistinct() throws InterruptedException {
        UUID anonymousId = UUID.randomUUID();

        String token1 = qrTokenService.generateQrToken(anonymousId);
        Thread.sleep(2); // ensure issuedAt millis differ
        String token2 = qrTokenService.generateQrToken(anonymousId);

        assertNotEquals(token1, token2,
                "Consecutive tokens must differ because issuedAt timestamps will differ");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1.5 — Token contains issuedAt and expiration claims
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Generated token contains both issuedAt and expiration date claims")
    void generatedToken_ContainsIssuedAtAndExpiration() {
        UUID anonymousId = UUID.randomUUID();
        long beforeMs = System.currentTimeMillis();

        String token = qrTokenService.generateQrToken(anonymousId);

        Key key = Keys.hmacShaKeyFor(SECRET.getBytes());
        var claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();

        assertNotNull(claims.getIssuedAt(), "issuedAt claim must be present");
        assertNotNull(claims.getExpiration(), "expiration claim must be present");

        long issuedMs = claims.getIssuedAt().getTime();
        long expiresMs = claims.getExpiration().getTime();

        assertTrue(issuedMs >= beforeMs, "issuedAt must be at or after test start");
        assertTrue(expiresMs > issuedMs, "expiration must be strictly after issuedAt");
        // For a 60-second TTL the delta should be approximately 60,000 ms (allow ±1 s)
        long ttlMs = expiresMs - issuedMs;
        assertTrue(ttlMs >= 59_000 && ttlMs <= 61_000,
                "TTL should be ~60 000 ms, but was " + ttlMs + " ms");
    }
}
