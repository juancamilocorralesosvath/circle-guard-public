package com.circleguard.gateway.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.data.redis.RedisConnectionFailureException;

import java.security.Key;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class QrValidationServiceTest {

    private QrValidationService service;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private final String secret = "my-super-secret-test-key-32-chars-long";

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOps);
        
        service = new QrValidationService(redisTemplate);
        ReflectionTestUtils.setField(service, "qrSecret", secret);
    }

    @Test
    void shouldValidateCorrectTokenAndAllowAccess() {
        String anonymousId = UUID.randomUUID().toString();
        Key key = Keys.hmacShaKeyFor(secret.getBytes());
        String token = Jwts.builder()
                .setSubject(anonymousId)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        Mockito.when(valueOps.get("user:status:" + anonymousId)).thenReturn("CLEAR");

        QrValidationService.ValidationResult result = service.validateToken(token);
        
        assertTrue(result.valid());
        assertEquals("GREEN", result.status());
    }

    @Test
    void fallback_whenRedisIsDown_grantsAccess() {
        RedisConnectionFailureException redisDown =
                new RedisConnectionFailureException("Redis unavailable");

        QrValidationService.ValidationResult result =
                service.fallbackValidateToken("any-token", redisDown);

        assertTrue(result.valid());
        assertEquals("UNKNOWN", result.status());
        assertTrue(result.message().contains("temporarily unavailable"));
    }

    @Test
    void shouldDenyAccessForSuspectUser() {
        String anonymousId = UUID.randomUUID().toString();
        Key key = Keys.hmacShaKeyFor(secret.getBytes());
        String token = Jwts.builder()
                .setSubject(anonymousId)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        Mockito.when(valueOps.get("user:status:" + anonymousId)).thenReturn("SUSPECT");

        QrValidationService.ValidationResult result = service.validateToken(token);
        
        assertFalse(result.valid());
        assertEquals("RED", result.status());
    }

    @Test
    void shouldDenyAccessForProbableUser() {
        QrValidationService.ValidationResult result = validateWithHealthStatus("PROBABLE");

        assertFalse(result.valid());
        assertEquals("RED", result.status());
        assertEquals("Access Denied: Health Risk Detected", result.message());
    }

    @Test
    void shouldDenyAccessForConfirmedUser() {
        QrValidationService.ValidationResult result = validateWithHealthStatus("CONFIRMED");

        assertFalse(result.valid());
        assertEquals("RED", result.status());
    }

    @Test
    void shouldAllowAccessWhenNoStatusExists() {
        QrValidationService.ValidationResult result = validateWithHealthStatus(null);

        assertTrue(result.valid());
        assertEquals("GREEN", result.status());
        assertEquals("Welcome to Campus", result.message());
    }

    @Test
    void shouldRejectInvalidToken() {
        QrValidationService.ValidationResult result = service.validateToken("not-a-jwt");

        assertFalse(result.valid());
        assertEquals("RED", result.status());
        assertEquals("Invalid or Expired Token", result.message());
    }

    private QrValidationService.ValidationResult validateWithHealthStatus(String status) {
        String anonymousId = UUID.randomUUID().toString();
        Key key = Keys.hmacShaKeyFor(secret.getBytes());
        String token = Jwts.builder()
                .setSubject(anonymousId)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        Mockito.when(valueOps.get("user:status:" + anonymousId)).thenReturn(status);

        return service.validateToken(token);
    }
}
