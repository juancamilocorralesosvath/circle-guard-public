package com.circleguard.form.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void shouldHandleIllegalArgumentException() {
        var response = new GlobalExceptionHandler().handleIllegalArgument(new IllegalArgumentException("bad input"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("bad input", response.getBody().get("error"));
    }

    @Test
    void shouldHandleGenericException() {
        var response = new GlobalExceptionHandler().handleGenericException(new RuntimeException("broken"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Invalid request: broken", response.getBody().get("error"));
    }
}
