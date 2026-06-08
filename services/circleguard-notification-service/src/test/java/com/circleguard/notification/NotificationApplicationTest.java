package com.circleguard.notification;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.mockStatic;

class NotificationApplicationTest {

    @Test
    void shouldDelegateToSpringApplication() {
        String[] args = {"--spring.profiles.active=test"};

        try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
            NotificationApplication.main(args);

            springApplication.verify(() -> SpringApplication.run(NotificationApplication.class, args));
        }
    }
}
