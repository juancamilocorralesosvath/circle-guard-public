package com.circleguard.promotion.controller;

import com.circleguard.promotion.exception.FenceException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerUnitTest {

    @Test
    void shouldMapKnownExceptionsToHttpResponses() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        assertEquals(403, handler.handleFenceException(new FenceException("fenced")).getStatusCode().value());
        assertEquals(400, handler.handleIllegalArgument(new IllegalArgumentException("bad")).getStatusCode().value());
        assertEquals(409, handler.handleIllegalState(new IllegalStateException("conflict")).getStatusCode().value());
        assertEquals(403, handler.handleAccessDenied(new AccessDeniedException(null)).getStatusCode().value());
        assertEquals("Access Denied", handler.handleAccessDenied(new AccessDeniedException(null)).getBody().get("error"));
        assertEquals(500, handler.handleGenericException(new RuntimeException("broken")).getStatusCode().value());
    }
}
