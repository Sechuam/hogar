package com.hogar.auth.service;

import com.hogar.auth.dto.AuthResponse;
import com.hogar.auth.dto.GoogleLoginRequest;
import com.hogar.auth.dto.LoginRequest;
import com.hogar.auth.dto.RefreshTokenRequest;
import com.hogar.auth.dto.RegisterRequest;
import com.hogar.auth.service.GoogleIdTokenVerifier.GoogleIdentity;
import com.hogar.shared.error.UnauthorizedException;
import com.hogar.user.dto.GoogleSignIn;
import com.hogar.user.dto.UserResponse;
import com.hogar.user.service.UserAccounts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de autenticación. Delega las cuentas en {@link UserAccounts} (feature user) y
 * emite el par access token + refresh token.
 */
@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "invalid-credentials";
    static final String GOOGLE_EMAIL_NOT_VERIFIED = "google-email-not-verified";
    static final int MAX_DISPLAY_NAME = 100;

    private final UserAccounts userAccounts;
    private final GoogleIdTokenVerifier googleVerifier;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenService refreshTokens;

    public AuthService(UserAccounts userAccounts, GoogleIdTokenVerifier googleVerifier,
                       AccessTokenIssuer accessTokens, RefreshTokenService refreshTokens) {
        this.userAccounts = userAccounts;
        this.googleVerifier = googleVerifier;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        UserResponse user = userAccounts.registerWithPassword(request.email(), request.password(), request.displayName());
        return startSession(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        UserResponse user = userAccounts.authenticate(request.email(), request.password())
                .orElseThrow(() -> new UnauthorizedException(INVALID_CREDENTIALS, "Email o contraseña incorrectos."));
        return startSession(user);
    }

    /**
     * Sin transacción propia a propósito: la verificación puede descargar el JWKS de Google y no
     * debe retener una conexión a BD mientras tanto. La vinculación y la emisión del refresh
     * token van cada una en su transacción.
     */
    public AuthResponse google(GoogleLoginRequest request) {
        GoogleIdentity identity = googleVerifier.verify(request.idToken());
        if (!identity.emailVerified()) {
            throw new UnauthorizedException(GOOGLE_EMAIL_NOT_VERIFIED,
                    "La cuenta de Google no tiene el email verificado.");
        }
        GoogleSignIn signIn = userAccounts.signInWithGoogle(
                identity.subject(), identity.email(), displayNameFor(identity));
        if (signIn.credentialsReset()) {
            refreshTokens.revokeAllForUser(signIn.user().id());
        }
        return startSession(signIn.user());
    }

    /** Ver {@link RefreshTokenService#rotate}: la revocación por reutilización se confirma pese al 401. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(request.refreshToken());
        UserResponse user = userAccounts.findProfile(rotation.userId())
                .orElseThrow(() -> new UnauthorizedException(RefreshTokenService.INVALID_REFRESH_TOKEN,
                        "El refresh token no es válido."));
        return respond(user, rotation.refreshToken());
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        refreshTokens.revokeFamilyOf(request.refreshToken());
    }

    private AuthResponse startSession(UserResponse user) {
        return respond(user, refreshTokens.issue(user.id()));
    }

    private AuthResponse respond(UserResponse user, String refreshToken) {
        AccessTokenIssuer.AccessToken access = accessTokens.issue(user.id());
        return AuthResponse.bearer(access.value(), access.expiresIn(), refreshToken, user);
    }

    /** {@code name} de Google, o la parte local del email si no viene; recortado al máximo de la columna. */
    static String displayNameFor(GoogleIdentity identity) {
        String name = identity.name();
        if (name == null || name.isBlank()) {
            String email = identity.email();
            int at = email.indexOf('@');
            name = at > 0 ? email.substring(0, at) : email;
        }
        name = name.strip();
        return name.length() > MAX_DISPLAY_NAME ? name.substring(0, MAX_DISPLAY_NAME) : name;
    }
}
