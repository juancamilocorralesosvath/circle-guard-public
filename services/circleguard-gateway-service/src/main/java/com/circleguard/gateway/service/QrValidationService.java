package com.circleguard.gateway.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.security.Key;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QrValidationService {
    private static final Logger log = LoggerFactory.getLogger(QrValidationService.class);
    private final StringRedisTemplate redisTemplate;

    @Value("${qr.secret}")
    private String qrSecret;

    private static final String STATUS_KEY_PREFIX = "user:status:";

    @CircuitBreaker(name = "redis", fallbackMethod = "fallbackValidateToken")
    public ValidationResult validateToken(String token) {
        try {
            Key key = Keys.hmacShaKeyFor(qrSecret.getBytes());
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String anonymousId = claims.getSubject();
            
            // Check Redis for current Health Status
            String status = redisTemplate.opsForValue().get(STATUS_KEY_PREFIX + anonymousId);
            
            if ("SUSPECT".equals(status) || "PROBABLE".equals(status) || "CONFIRMED".equals(status)) {
                return new ValidationResult(false, "RED", "Access Denied: Health Risk Detected");
            }

            return new ValidationResult(true, "GREEN", "Welcome to Campus");
            
        } catch (Exception e) {
            return new ValidationResult(false, "RED", "Invalid or Expired Token");
        }
    }

    ValidationResult fallbackValidateToken(String token, Throwable ex) {
        log.warn("Redis unavailable, granting fail-open access. Cause: {}", ex.getMessage());
        return new ValidationResult(true, "UNKNOWN", "Access granted — validation service temporarily unavailable");
    }

    public record ValidationResult(boolean valid, String status, String message) {}
}
