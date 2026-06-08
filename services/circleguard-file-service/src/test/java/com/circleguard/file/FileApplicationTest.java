package com.circleguard.file;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.mockStatic;

class FileApplicationTest {

    @Test
    void shouldDelegateToSpringApplication() {
        String[] args = {"--spring.profiles.active=test"};

        try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
            FileApplication.main(args);

            springApplication.verify(() -> SpringApplication.run(FileApplication.class, args));
        }
    }
}
