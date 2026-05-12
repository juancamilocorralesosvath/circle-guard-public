package com.circleguard.auth.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Test 4: DualChainAuthenticationProvider – LDAP → Local DB Fallback
 *
 * WHY CRITICAL:
 *   Guest lecturers, admin accounts, and Health Center staff authenticate against
 *   the local PostgreSQL user table (not LDAP). If the fallback chain is broken,
 *   all non-LDAP users are permanently locked out, preventing health staff from
 *   registering confirmed cases — a direct patient-safety risk.
 *
 *   Additionally, the LDAP server is an external dependency that may be
 *   temporarily unavailable; the fallback prevents a single-point-of-failure
 *   from blocking all campus access during an outage.
 *
 * WHAT IS VALIDATED:
 *   1. LDAP succeeds → local DB is NOT attempted (efficiency).
 *   2. LDAP fails (BadCredentialsException) → local DB is attempted.
 *   3. LDAP unavailable (ConnectionException) → local DB provides successful auth.
 *   4. Both LDAP and local DB fail → propagates the local DB exception.
 *   5. Disabled account in local DB → DisabledException propagates correctly.
 *   6. Provider supports UsernamePasswordAuthenticationToken.
 */
@ExtendWith(MockitoExtension.class)
class DualChainAuthenticationProviderTest {

    @Mock
    private LdapAuthenticationProvider ldapProvider;

    @Mock
    private DaoAuthenticationProvider localProvider;

    private DualChainAuthenticationProvider dualChain;

    @BeforeEach
    void setUp() {
        dualChain = new DualChainAuthenticationProvider(ldapProvider, localProvider);
    }

    private UsernamePasswordAuthenticationToken token(String user) {
        return new UsernamePasswordAuthenticationToken(user, "password");
    }

    private Authentication successfulAuth(String user) {
        return new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4.1 — LDAP succeeds → local DB NOT called
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("When LDAP succeeds, local DB provider is never invoked")
    void ldapSuccess_LocalProviderNotCalled() {
        Authentication ldapAuth = successfulAuth("ldap-user");
        when(ldapProvider.authenticate(any())).thenReturn(ldapAuth);

        Authentication result = dualChain.authenticate(token("ldap-user"));

        assertNotNull(result);
        assertEquals("ldap-user", result.getPrincipal());
        verify(localProvider, never()).authenticate(any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4.2 — LDAP fails → local DB is attempted
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("When LDAP fails with BadCredentialsException, local DB provider is tried")
    void ldapFails_LocalProviderIsInvoked() {
        when(ldapProvider.authenticate(any()))
                .thenThrow(new BadCredentialsException("LDAP: invalid credentials"));
        Authentication localAuth = successfulAuth("local-user");
        when(localProvider.authenticate(any())).thenReturn(localAuth);

        Authentication result = dualChain.authenticate(token("local-user"));

        assertNotNull(result, "Result must not be null when local provider succeeds");
        verify(localProvider, times(1)).authenticate(any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4.3 — LDAP connection exception → local DB provides successful auth
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("LDAP connection failure causes transparent fallback to local DB")
    void ldapConnectionError_FallsBackToLocalDb_Successfully() {
        when(ldapProvider.authenticate(any()))
                .thenThrow(new org.springframework.security.core.AuthenticationException("LDAP server unreachable") {});
        Authentication localAuth = successfulAuth("health_user");
        when(localProvider.authenticate(any())).thenReturn(localAuth);

        Authentication result = dualChain.authenticate(token("health_user"));

        assertNotNull(result);
        assertEquals("health_user", result.getPrincipal());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4.4 — Both LDAP and local DB fail → exception propagates
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("When both LDAP and local DB fail, the local DB exception is propagated")
    void bothFail_LocalExceptionPropagates() {
        when(ldapProvider.authenticate(any()))
                .thenThrow(new BadCredentialsException("LDAP denied"));
        when(localProvider.authenticate(any()))
                .thenThrow(new BadCredentialsException("Local DB: wrong password"));

        assertThrows(BadCredentialsException.class,
                () -> dualChain.authenticate(token("unknown-user")),
                "Exception from local provider must propagate when both chains fail");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4.5 — Disabled account in local DB → DisabledException propagates
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Disabled local user causes DisabledException to propagate from local DB")
    void disabledLocalUser_DisabledExceptionPropagates() {
        when(ldapProvider.authenticate(any()))
                .thenThrow(new BadCredentialsException("LDAP: not found"));
        when(localProvider.authenticate(any()))
                .thenThrow(new DisabledException("Account is disabled"));

        assertThrows(DisabledException.class,
                () -> dualChain.authenticate(token("inactive-user")),
                "DisabledException from local provider must propagate");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4.6 — Provider supports UsernamePasswordAuthenticationToken
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("supports() returns true for UsernamePasswordAuthenticationToken")
    void supports_UsernamePasswordToken_ReturnsTrue() {
        assertTrue(dualChain.supports(UsernamePasswordAuthenticationToken.class),
                "DualChainProvider must support UsernamePasswordAuthenticationToken");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4.7 — Provider does not support other token types
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("supports() returns false for anonymous / other token types")
    void supports_AnonymousToken_ReturnsFalse() {
        assertFalse(dualChain.supports(
                        org.springframework.security.authentication.AnonymousAuthenticationToken.class),
                "DualChainProvider must NOT support AnonymousAuthenticationToken");
    }
}
