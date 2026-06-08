package com.circleguard.notification.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

class SmsServiceImplTest {

    @Test
    void shouldSkipTwilioInitForMockSidAndAuditMockSms() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        SmsServiceImpl service = new SmsServiceImpl();
        ReflectionTestUtils.setField(service, "accountSid", "AC_MOCK_SID");
        ReflectionTestUtils.setField(service, "authToken", "MOCK_TOKEN");
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);

        service.init();
        service.sendAsync("user-1", "message").join();

        verify(auditLogService).logDelivery(eq("user-1"), eq("SMS"), eq("SUCCESS"), any());
    }

    @Test
    void shouldAuditFailedSmsRecovery() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        SmsServiceImpl service = new SmsServiceImpl();
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);

        assertThrows(CompletionException.class,
                () -> service.recover(new RuntimeException("down"), "user-1", "message").join());

        verify(auditLogService).logDelivery("user-1", "SMS", "FAILED", null);
    }

    @Test
    void shouldInitializeTwilioAndSendRealSmsWhenConfigured() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        MessageCreator creator = mock(MessageCreator.class);
        SmsServiceImpl service = new SmsServiceImpl();
        ReflectionTestUtils.setField(service, "accountSid", "AC_REAL_SID");
        ReflectionTestUtils.setField(service, "authToken", "REAL_TOKEN");
        ReflectionTestUtils.setField(service, "fromNumber", "+15550000000");
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);

        try (MockedStatic<Twilio> twilio = mockStatic(Twilio.class);
             MockedStatic<Message> message = mockStatic(Message.class)) {
            message.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), eq("message")))
                    .thenReturn(creator);

            service.init();
            service.sendAsync("user-1", "message").join();

            twilio.verify(() -> Twilio.init("AC_REAL_SID", "REAL_TOKEN"));
            verify(creator).create();
            verify(auditLogService).logDelivery(eq("user-1"), eq("SMS"), eq("SUCCESS"), any());
        }
    }

    @Test
    void shouldAuditRetryWhenRealSmsFails() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        SmsServiceImpl service = new SmsServiceImpl();
        ReflectionTestUtils.setField(service, "accountSid", "AC_REAL_SID");
        ReflectionTestUtils.setField(service, "fromNumber", "+15550000000");
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);

        try (MockedStatic<Message> message = mockStatic(Message.class)) {
            message.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), eq("message")))
                    .thenThrow(new RuntimeException("twilio down"));

            assertThrows(RuntimeException.class, () -> service.sendAsync("user-1", "message"));
        }

        verify(auditLogService).logDelivery(eq("user-1"), eq("SMS"), eq("RETRY"), any());
    }
}
