package com.circleguard.auth.controller;

import com.circleguard.auth.metrics.AuthMetrics;
import com.circleguard.auth.service.QrTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QrTokenControllerTest {

    @Test
    void shouldGenerateQrTokenForAuthenticatedUser() {
        QrTokenService qrTokenService = mock(QrTokenService.class);
        AuthMetrics metrics = mock(AuthMetrics.class);
        QrTokenController controller = new QrTokenController(qrTokenService, metrics);
        UUID anonymousId = UUID.randomUUID();
        when(qrTokenService.generateQrToken(anonymousId)).thenReturn("qr-token");

        ResponseEntity<Map<String, String>> response =
                controller.generateToken(new UsernamePasswordAuthenticationToken(anonymousId.toString(), null));

        assertEquals("qr-token", response.getBody().get("qrToken"));
        assertEquals("60", response.getBody().get("expiresIn"));
        verify(metrics).recordQrGenerated();
    }
}
