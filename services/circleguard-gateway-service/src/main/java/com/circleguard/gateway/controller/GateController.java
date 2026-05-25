package com.circleguard.gateway.controller;

import com.circleguard.gateway.metrics.GatewayMetrics;
import com.circleguard.gateway.service.QrValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gate")
@RequiredArgsConstructor
public class GateController {

    private final QrValidationService validationService;
    private final GatewayMetrics gatewayMetrics;

    @PostMapping("/validate")
    public ResponseEntity<QrValidationService.ValidationResult> validate(@RequestBody Map<String, String> request) {
        String token = request.get("token");
        QrValidationService.ValidationResult result = validationService.validateToken(token);

        if (result.valid()) {
            gatewayMetrics.recordQrValidated();
        } else {
            gatewayMetrics.recordQrRejected();
        }

        return ResponseEntity.ok(result);
    }
}
