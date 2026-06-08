package com.circleguard.form.controller;

import com.circleguard.form.service.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttachmentControllerUnitTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnStoredFilename() {
        StorageService storageService = mock(StorageService.class);
        MockMultipartFile file = new MockMultipartFile("file", "proof.pdf", "application/pdf", "content".getBytes());
        when(storageService.store(file)).thenReturn("stored-proof.pdf");

        var response = new AttachmentController(storageService).upload(file);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("stored-proof.pdf", ((Map<String, String>) response.getBody()).get("filename"));
    }
}
