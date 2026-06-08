package com.circleguard.notification.service;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EmailServiceImplUnitTest {

    @Test
    void shouldSendEmailAndAuditSuccess() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        AuditLogService auditLogService = mock(AuditLogService.class);

        new EmailServiceImpl(mailSender, auditLogService).sendAsync("alice", "hello").join();

        verify(mailSender).send(any(SimpleMailMessage.class));
        verify(auditLogService).logDelivery(eq("alice"), eq("EMAIL"), eq("SUCCESS"), any());
    }

    @Test
    void shouldAuditRetryWhenEmailFails() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        doThrow(new RuntimeException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> new EmailServiceImpl(mailSender, auditLogService).sendAsync("alice", "hello"));

        assertEquals("smtp down", exception.getMessage());
        verify(auditLogService).logDelivery(eq("alice"), eq("EMAIL"), eq("RETRY"), any());
    }

    @Test
    void shouldAuditFailedEmailRecovery() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        EmailServiceImpl service = new EmailServiceImpl(mock(JavaMailSender.class), auditLogService);

        assertThrows(CompletionException.class,
                () -> service.recover(new RuntimeException("smtp down"), "alice", "hello").join());

        verify(auditLogService).logDelivery("alice", "EMAIL", "FAILED", null);
    }
}
