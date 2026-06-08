package com.circleguard.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenServiceTest {

    @Test
    void shouldGenerateTokenWithAnonymousIdAndPermissions() {
        String secret = "my-super-secret-test-key-32-chars-long";
        JwtTokenService service = new JwtTokenService(secret, 60_000);
        UUID anonymousId = UUID.randomUUID();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "user",
                null,
                List.of(new SimpleGrantedAuthority("health:write"), new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        String token = service.generateToken(anonymousId, auth);

        Claims claims = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(secret.getBytes()))
                .build()
                .parseClaimsJws(token)
                .getBody();
        assertEquals(anonymousId.toString(), claims.getSubject());
        List<?> permissions = claims.get("permissions", List.class);
        assertTrue(permissions.contains("health:write"));
        assertTrue(permissions.contains("ROLE_ADMIN"));
    }
}
