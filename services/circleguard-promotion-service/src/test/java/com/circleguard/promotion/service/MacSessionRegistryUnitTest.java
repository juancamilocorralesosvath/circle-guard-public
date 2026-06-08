package com.circleguard.promotion.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MacSessionRegistryUnitTest {

    @Test
    void shouldRegisterResolveAndCloseMacSessionsLowercase() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("session:mac:aa:bb")).thenReturn("u1");
        MacSessionRegistry registry = new MacSessionRegistry(redisTemplate);

        registry.registerSession("AA:BB", "u1");
        assertEquals("u1", registry.getAnonymousId("AA:BB"));
        registry.closeSession("AA:BB");

        verify(valueOperations).set("session:mac:aa:bb", "u1", Duration.ofHours(8));
        verify(redisTemplate).delete("session:mac:aa:bb");
    }
}
