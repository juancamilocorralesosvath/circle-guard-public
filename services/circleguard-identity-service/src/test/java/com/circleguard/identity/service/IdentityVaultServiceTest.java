package com.circleguard.identity.service;

import com.circleguard.identity.model.IdentityMapping;
import com.circleguard.identity.repository.IdentityMappingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityVaultServiceTest {

    @Test
    void shouldReturnExistingAnonymousIdForKnownIdentityHash() {
        IdentityMappingRepository repository = mock(IdentityMappingRepository.class);
        UUID anonymousId = UUID.randomUUID();
        when(repository.findByIdentityHash(anyString()))
                .thenReturn(Optional.of(IdentityMapping.builder().anonymousId(anonymousId).build()));
        IdentityVaultService service = newService(repository);

        assertEquals(anonymousId, service.getOrCreateAnonymousId("alice@example.edu"));
        verify(repository, never()).save(any());
    }

    @Test
    void shouldCreateMappingWhenIdentityIsUnknown() {
        IdentityMappingRepository repository = mock(IdentityMappingRepository.class);
        UUID anonymousId = UUID.randomUUID();
        when(repository.findByIdentityHash(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(IdentityMapping.class))).thenAnswer(invocation -> {
            IdentityMapping mapping = invocation.getArgument(0);
            mapping.setAnonymousId(anonymousId);
            return mapping;
        });
        IdentityVaultService service = newService(repository);

        assertEquals(anonymousId, service.getOrCreateAnonymousId("alice@example.edu"));

        ArgumentCaptor<IdentityMapping> mapping = ArgumentCaptor.forClass(IdentityMapping.class);
        verify(repository).save(mapping.capture());
        assertEquals("alice@example.edu", mapping.getValue().getRealIdentity());
        assertEquals(64, mapping.getValue().getIdentityHash().length());
        assertNotNull(mapping.getValue().getSalt());
    }

    @Test
    void shouldResolveRealIdentity() {
        IdentityMappingRepository repository = mock(IdentityMappingRepository.class);
        UUID anonymousId = UUID.randomUUID();
        when(repository.findById(anonymousId))
                .thenReturn(Optional.of(IdentityMapping.builder().realIdentity("alice@example.edu").build()));

        assertEquals("alice@example.edu", newService(repository).resolveRealIdentity(anonymousId));
    }

    @Test
    void shouldThrowNotFoundWhenIdentityCannotBeResolved() {
        IdentityMappingRepository repository = mock(IdentityMappingRepository.class);
        UUID anonymousId = UUID.randomUUID();
        when(repository.findById(anonymousId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> newService(repository).resolveRealIdentity(anonymousId));

        assertTrue(exception.getMessage().contains("Identity not found"));
    }

    private IdentityVaultService newService(IdentityMappingRepository repository) {
        IdentityVaultService service = new IdentityVaultService(repository);
        ReflectionTestUtils.setField(service, "hashSalt", "test-salt");
        return service;
    }
}
