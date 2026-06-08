package com.circleguard.form;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.mockStatic;

class FormApplicationTest {

    @Test
    void shouldDelegateToSpringApplication() {
        String[] args = {"--spring.profiles.active=test"};

        try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
            FormApplication.main(args);

            springApplication.verify(() -> SpringApplication.run(FormApplication.class, args));
        }
    }
}
