package com.circleguard.notification.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class LmsServiceTest {

    @MockBean
    private JavaMailSender mailSender;

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private LmsService lmsService;

    @Test
    void testRemoteAttendanceSync() {
        CompletableFuture<Void> future = lmsService.syncRemoteAttendance("student-123", "PROBABLE");
        future.join(); // Wait for completion
        assertThat(future).isCompleted();
    }
}
