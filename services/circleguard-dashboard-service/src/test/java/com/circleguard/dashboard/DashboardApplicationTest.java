package com.circleguard.dashboard;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.mockStatic;

class DashboardApplicationTest {

    @Test
    void shouldDelegateToSpringApplication() {
        String[] args = {"--spring.profiles.active=test"};

        try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
            DashboardApplication.main(args);

            springApplication.verify(() -> SpringApplication.run(DashboardApplication.class, args));
        }
    }
}
