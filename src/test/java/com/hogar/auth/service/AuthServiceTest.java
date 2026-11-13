package com.hogar.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hogar.auth.dto.AuthResponse;
import com.hogar.auth.dto.GoogleLoginRequest;
import com.hogar.auth.dto.LoginRequest;
import com.hogar.auth.dto.RefreshTokenRequest;
import com.hogar.auth.dto.RegisterRequest;
import com.hogar.auth.service.GoogleIdTokenVerifier.GoogleIdentity;
import com.hogar.shared.error.ConflictException;
import com.hogar.shared.error.UnauthorizedException;
import com.hogar.user.dto.UserResponse;
import com.hogar.user.service.UserAccounts;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final UserResponse ANA = new UserResponse(UUID.randomUUID(), "ana@example.com", "Ana", true, List.of());

    @Mock
    private UserAccounts userAccounts;

    @Mock
    private GoogleIdTokenVerifier googleVerifier;

    @Mock
    private AccessTokenIssuer accessTokens;

    @Mock
    private RefreshTokenService refreshTokens;

    @InjectMocks
    private AuthService service;

    @Test
    void registerCreatesAccountAndStartsSession() {
        when(userAccounts.registerWithPassword("ana@example.com", "secreto-123", "Ana")).thenReturn(ANA);
        givenTokensFor(ANA.id());

        AuthResponse response = service.register(new RegisterRequest("ana@example.com", "secreto-123", "Ana"));

        assertThat(response).isEqualTo(new AuthResponse("access", "Bearer", 900, "refresh", ANA));
    }

    @Test
    void registerDuplicateDoesNotIssueTokens() {
        when(userAccounts.registerWithPassword(anyString(), anyString(), anyString()))
                .thenThrow(new ConflictException("email-already-registered", "dup"));

        assertThatThrownBy(() -> service.register(new RegisterRequest("ana@example.com", "secreto-123", "Ana")))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(refreshTokens, accessTokens);
    }

    @Test
    void loginWithValidCredentials() {
        when(userAccounts.authenticate("ana@example.com", "secreto-123")).thenReturn(Optional.of(ANA));
        givenTokensFor(ANA.id());

        AuthResponse response = service.login(new LoginRequest("ana@example.com", "secreto-123"));

        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
        assertThat(response.user()).isEqualTo(ANA);
    }

    @Test
    void loginWithInvalidCredentialsIsGeneric401() {
        when(userAccounts.authenticate("ana@example.com", "mala")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("ana@example.com", "mala")))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("invalid-credentials");
        verifyNoInteractions(refreshTokens, accessTokens);
    }

    @Test
    void googleWithVerifiedEmailSignsIn() {
        when(googleVerifier.verify("id-token"))
                .thenReturn(new GoogleIdentity("sub-1", "ana@gmail.com", true, "Ana García"));
        when(userAccounts.signInWithGoogle("sub-1", "ana@gmail.com", "Ana García")).thenReturn(ANA);
        givenTokensFor(ANA.id());

        assertThat(service.google(new GoogleLoginRequest("id-token")).user()).isEqualTo(ANA);
    }

    @Test
    void googleWithUnverifiedEmailIsRejectedBeforeTouchingAccounts() {
        when(googleVerifier.verify("id-token"))
                .thenReturn(new GoogleIdentity("sub-1", "ana@gmail.com", false, "Ana"));

        assertThatThrownBy(() -> service.google(new GoogleLoginRequest("id-token")))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("google-email-not-verified");
        verifyNoInteractions(userAccounts, refreshTokens);
    }

    @Test
    void googleDisplayNameFallsBackToEmailLocalPartAndIsTruncated() {
        assertThat(AuthService.displayNameFor(new GoogleIdentity("s", "pepe@gmail.com", true, null))).isEqualTo("pepe");
        assertThat(AuthService.displayNameFor(new GoogleIdentity("s", "pepe@gmail.com", true, "  ")))
                .isEqualTo("pepe");
        assertThat(AuthService.displayNameFor(new GoogleIdentity("s", "p@gmail.com", true, "x".repeat(150))))
                .hasSize(100);
    }

    @Test
    void refreshRotatesAndIssuesNewAccessToken() {
        when(refreshTokens.rotate("viejo")).thenReturn(new RefreshTokenService.Rotation(ANA.id(), "nuevo"));
        when(userAccounts.findProfile(ANA.id())).thenReturn(Optional.of(ANA));
        when(accessTokens.issue(ANA.id())).thenReturn(new AccessTokenIssuer.AccessToken("access", 900));

        AuthResponse response = service.refresh(new RefreshTokenRequest("viejo"));

        assertThat(response.refreshToken()).isEqualTo("nuevo");
        assertThat(response.accessToken()).isEqualTo("access");
        verify(refreshTokens, never()).issue(any());
    }

    @Test
    void refreshForDeletedUserIsInvalid() {
        when(refreshTokens.rotate("viejo")).thenReturn(new RefreshTokenService.Rotation(ANA.id(), "nuevo"));
        when(userAccounts.findProfile(ANA.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh(new RefreshTokenRequest("viejo")))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("invalid-refresh-token");
    }

    @Test
    void logoutRevokesTheFamily() {
        service.logout(new RefreshTokenRequest("sesion"));

        verify(refreshTokens).revokeFamilyOf("sesion");
    }

    private void givenTokensFor(UUID userId) {
        when(refreshTokens.issue(userId)).thenReturn("refresh");
        when(accessTokens.issue(userId)).thenReturn(new AccessTokenIssuer.AccessToken("access", 900));
    }
}
