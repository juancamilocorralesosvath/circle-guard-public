package com.circleguard.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class QrTokenService {

    private final Key key;
    private final long expiration;
    private final Clock clock;

    @Autowired
    public QrTokenService(@Value("${qr.secret}") String secret,
            @Value("${qr.expiration:60000}") long expiration) {
        this(secret, expiration, Clock.systemUTC());
    }

    // Package-private so Spring only autowires the @Value constructor; tests in this package pass a Clock.
    QrTokenService(String secret, long expiration, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.expiration = expiration;
        this.clock = clock;
    }

    public String generateQrToken(UUID anonymousId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .setSubject(anonymousId.toString())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusMillis(expiration)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }
}
