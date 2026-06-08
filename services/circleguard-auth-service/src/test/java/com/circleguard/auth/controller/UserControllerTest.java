package com.circleguard.auth.controller;

import com.circleguard.auth.model.LocalUser;
import com.circleguard.auth.repository.LocalUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserControllerTest {

    @Test
    void shouldReturnUsersWithRequestedPermission() {
        LocalUserRepository repository = mock(LocalUserRepository.class);
        LocalUser alice = LocalUser.builder()
                .username("alice")
                .email("alice@example.edu")
                .build();
        LocalUser bob = LocalUser.builder()
                .username("bob")
                .build();
        when(repository.findUsersByPermissionName("alert:receive_priority"))
                .thenReturn(List.of(alice, bob));

        ResponseEntity<List<Map<String, String>>> response =
                new UserController(repository).getUsersByPermission("alert:receive_priority");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(
                Map.of("username", "alice", "email", "alice@example.edu"),
                Map.of("username", "bob", "email", "")
        ), response.getBody());
    }
}
