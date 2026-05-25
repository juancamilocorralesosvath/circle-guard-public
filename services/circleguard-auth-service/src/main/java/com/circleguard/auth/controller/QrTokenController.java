package com.circleguard.auth.controller;

import com.circleguard.auth.metrics.AuthMetrics;
import com.circleguard.auth.service.QrTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/qr")
@RequiredArgsConstructor
public class QrTokenController {

    private final QrTokenService qrService;
    private final AuthMetrics authMetrics;

    @GetMapping("/generate")
    public ResponseEntity<Map<String, String>> generateToken(Authentication auth) {
        UUID anonymousId = UUID.fromString(auth.getName());
        String token = qrService.generateQrToken(anonymousId);
        authMetrics.recordQrGenerated();

        return ResponseEntity.ok(Map.of(
            "qrToken", token,
            "expiresIn", "60"
        ));
    }
}
