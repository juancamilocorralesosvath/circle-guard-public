package com.circleguard.identity;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.mockStatic;

class IdentityServiceApplicationTest {

    @Test
    void shouldDelegateToSpringApplication() {
        String[] args = {"--spring.profiles.active=test"};

        try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
            IdentityServiceApplication.main(args);

            springApplication.verify(() -> SpringApplication.run(IdentityServiceApplication.class, args));
        }
    }
}
