package com.circleguard.form.service;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageServiceTest {

    private final List<Path> createdFiles = new ArrayList<>();

    @AfterEach
    void cleanUp() throws IOException {
        for (Path file : createdFiles) {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void shouldStoreMultipartFile() throws Exception {
        StorageService service = new StorageService();
        MockMultipartFile file = new MockMultipartFile("file", "proof.pdf", "application/pdf", "content".getBytes());

        String filename = service.store(file);
        Path storedPath = Path.of("/tmp/circleguard-uploads").resolve(filename);
        createdFiles.add(storedPath);

        assertTrue(filename.endsWith("_proof.pdf"));
        assertTrue(Files.exists(storedPath));
        assertArrayEquals("content".getBytes(), Files.readAllBytes(storedPath));
    }

    @Test
    void shouldWrapStoreFailures() throws Exception {
        StorageService service = new StorageService();
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("proof.pdf");
        when(file.getInputStream()).thenThrow(new IOException("no stream"));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.store(file));

        assertTrue(exception.getMessage().contains("Could not store the file"));
    }
}
