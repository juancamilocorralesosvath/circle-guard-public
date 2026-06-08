package com.circleguard.auth.service;

import com.circleguard.auth.model.LocalUser;
import com.circleguard.auth.model.Permission;
import com.circleguard.auth.model.Role;
import com.circleguard.auth.repository.LocalUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomUserDetailsServiceTest {

    @Test
    void shouldLoadActiveUserWithRolesAndPermissions() {
        LocalUserRepository repository = mock(LocalUserRepository.class);
        Permission permission = Permission.builder().name("health:write").build();
        Role role = Role.builder().name("ADMIN").permissions(Set.of(permission)).build();
        LocalUser user = LocalUser.builder()
                .username("alice")
                .password("hash")
                .isActive(true)
                .roles(Set.of(role))
                .build();
        when(repository.findByUsername("alice")).thenReturn(Optional.of(user));

        UserDetails details = new CustomUserDetailsService(repository).loadUserByUsername("alice");

        assertEquals("alice", details.getUsername());
        assertEquals("hash", details.getPassword());
        assertTrue(details.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        assertTrue(details.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("health:write")));
    }

    @Test
    void shouldRejectMissingUser() {
        LocalUserRepository repository = mock(LocalUserRepository.class);
        when(repository.findByUsername("missing")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class,
                () -> new CustomUserDetailsService(repository).loadUserByUsername("missing"));
    }

    @Test
    void shouldRejectDisabledUser() {
        LocalUserRepository repository = mock(LocalUserRepository.class);
        LocalUser user = LocalUser.builder()
                .username("disabled")
                .password("hash")
                .isActive(false)
                .roles(Set.of())
                .build();
        when(repository.findByUsername("disabled")).thenReturn(Optional.of(user));

        assertThrows(DisabledException.class,
                () -> new CustomUserDetailsService(repository).loadUserByUsername("disabled"));
    }
}
