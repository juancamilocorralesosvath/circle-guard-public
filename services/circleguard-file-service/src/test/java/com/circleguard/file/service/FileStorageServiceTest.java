package com.circleguard.file.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileStorageServiceTest {

    private final List<Path> createdFiles = new ArrayList<>();

    @AfterEach
    void cleanUp() throws IOException {
        for (Path file : createdFiles) {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void shouldSaveFileWithGeneratedPrefix() throws Exception {
        FileStorageService service = new FileStorageService();
        MockMultipartFile file = new MockMultipartFile(
                "file", "certificate.pdf", "application/pdf", "mock content".getBytes());

        String storedName = service.saveFile(file);
        Path storedPath = Path.of("uploads").resolve(storedName);
        createdFiles.add(storedPath);

        assertTrue(storedName.endsWith("_certificate.pdf"));
        assertTrue(Files.exists(storedPath));
        assertArrayEquals("mock content".getBytes(), Files.readAllBytes(storedPath));
    }

    @Test
    void shouldWrapStorageFailures() throws Exception {
        FileStorageService service = new FileStorageService();
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("broken.pdf");
        when(file.getInputStream()).thenThrow(new IOException("disk unavailable"));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.saveFile(file));

        assertTrue(exception.getMessage().contains("Could not store file"));
    }

    @Test
    void loadFileCurrentlyReturnsNull() {
        assertNull(new FileStorageService().loadFile("missing.pdf"));
    }
}
