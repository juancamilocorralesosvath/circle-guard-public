package com.circleguard.notification.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushServiceImplTest {

    @Test
    void shouldSendMockPushAndAuditSuccess() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        PushServiceImpl service = new PushServiceImpl(WebClient.builder(), "http://gotify");
        ReflectionTestUtils.setField(service, "gotifyToken", "MOCK_TOKEN");
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);

        service.sendAsync("user-1", "hello", Map.of("status", "SUSPECT")).join();

        verify(auditLogService).logDelivery(eq("user-1"), eq("PUSH"), eq("SUCCESS"), any());
    }

    @Test
    void shouldDelegateSimpleSendToMetadataVariant() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        PushServiceImpl service = new PushServiceImpl(WebClient.builder(), "http://gotify");
        ReflectionTestUtils.setField(service, "gotifyToken", "MOCK_TOKEN");
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);

        service.sendAsync("user-1", "hello").join();

        verify(auditLogService).logDelivery(eq("user-1"), eq("PUSH"), eq("SUCCESS"), any());
    }

    @Test
    void shouldAuditFailedPushRecovery() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        PushServiceImpl service = new PushServiceImpl(WebClient.builder(), "http://gotify");
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);

        assertThrows(CompletionException.class,
                () -> service.recover(new RuntimeException("down"), "user-1", "hello", Map.of()).join());

        verify(auditLogService).logDelivery("user-1", "PUSH", "FAILED", null);
    }

    @Test
    void shouldSendPushThroughGotifyWhenTokenIsConfigured() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        WebClient.Builder builder = mock(WebClient.Builder.class);
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec request = mock(WebClient.RequestBodyUriSpec.class);
        @SuppressWarnings("rawtypes")
        WebClient.RequestHeadersSpec headers = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec response = mock(WebClient.ResponseSpec.class);
        when(builder.baseUrl("http://gotify")).thenReturn(builder);
        when(builder.build()).thenReturn(webClient);
        when(webClient.post()).thenReturn(request);
        when(request.uri("/message?token=REAL_TOKEN")).thenReturn(request);
        when(request.contentType(MediaType.APPLICATION_JSON)).thenReturn(request);
        when(request.bodyValue(any())).thenReturn(headers);
        when(headers.retrieve()).thenReturn(response);
        when(response.toBodilessEntity()).thenReturn(Mono.just(ResponseEntity.ok().build()));
        PushServiceImpl service = new PushServiceImpl(builder, "http://gotify");
        ReflectionTestUtils.setField(service, "gotifyToken", "REAL_TOKEN");
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);

        service.sendAsync("user-1", "hello", Map.of("status", "CONFIRMED")).join();

        verify(auditLogService).logDelivery(eq("user-1"), eq("PUSH"), eq("SUCCESS"), any());
    }

    @Test
    void shouldAuditRetryWhenGotifyRequestFails() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        WebClient.Builder builder = mock(WebClient.Builder.class);
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec request = mock(WebClient.RequestBodyUriSpec.class);
        @SuppressWarnings("rawtypes")
        WebClient.RequestHeadersSpec headers = mock(WebClient.RequestHeadersSpec.class);
        when(builder.baseUrl("http://gotify")).thenReturn(builder);
        when(builder.build()).thenReturn(webClient);
        when(webClient.post()).thenReturn(request);
        when(request.uri("/message?token=REAL_TOKEN")).thenReturn(request);
        when(request.contentType(MediaType.APPLICATION_JSON)).thenReturn(request);
        when(request.bodyValue(any())).thenReturn(headers);
        when(headers.retrieve()).thenThrow(new RuntimeException("gotify down"));
        PushServiceImpl service = new PushServiceImpl(builder, "http://gotify");
        ReflectionTestUtils.setField(service, "gotifyToken", "REAL_TOKEN");
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);

        assertThrows(RuntimeException.class,
                () -> service.sendAsync("user-1", "hello", Map.of()));

        verify(auditLogService).logDelivery(eq("user-1"), eq("PUSH"), eq("RETRY"), any());
    }
}
