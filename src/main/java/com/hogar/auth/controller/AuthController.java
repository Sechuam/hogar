package com.hogar.auth.controller;

import com.hogar.auth.dto.AuthResponse;
import com.hogar.auth.dto.GoogleLoginRequest;
import com.hogar.auth.dto.LoginRequest;
import com.hogar.auth.dto.RefreshTokenRequest;
import com.hogar.auth.dto.RegisterRequest;
import com.hogar.auth.service.AuthRateLimiter;
import com.hogar.auth.service.AuthService;
import com.hogar.shared.config.ApiDoc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints públicos de autenticación. El rate limiting usa la IP de la conexión
 * ({@code getRemoteAddr}), nunca {@code X-Forwarded-For}, que el cliente puede falsificar.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = ApiDoc.TAG_AUTH)
@SecurityRequirements
@ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
class AuthController {

    private static final String RETRY_AFTER = "Retry-After";
    private static final String RETRY_AFTER_DOC = "Segundos que hay que esperar antes de reintentar.";

    private static final URI ME = URI.create("/api/users/me");

    private final AuthService authService;
    private final AuthRateLimiter rateLimiter;

    AuthController(AuthService authService, AuthRateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @Operation(summary = "Registrar una cuenta con email y contraseña",
            description = "Crea el usuario (email guardado en minúsculas) y abre sesión: devuelve access y "
                    + "refresh token. La contraseña tiene entre 8 y 72 caracteres (límite de BCrypt). "
                    + "Rate limiting por IP + email y por IP.")
    @ApiResponse(responseCode = "201", description = "Cuenta creada y sesión abierta.",
            headers = @Header(name = "Location", description = "`/api/users/me`", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "409", description = "`email-already-registered`: ya existe una cuenta con ese email.")
    @ApiResponse(responseCode = "429", description = ApiDoc.RATE_LIMITED,
            headers = @Header(name = RETRY_AFTER, description = RETRY_AFTER_DOC, schema = @Schema(type = "integer")))
    @PostMapping("/register")
    ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        rateLimiter.checkRegister(http.getRemoteAddr(), request.email());
        return ResponseEntity.created(ME).body(authService.register(request));
    }

    @Operation(summary = "Iniciar sesión con email y contraseña",
            description = "Devuelve un par de tokens nuevo. El mismo error si el email no existe, la contraseña "
                    + "no coincide o la cuenta solo entra con Google (no se revela qué cuentas existen).")
    @ApiResponse(responseCode = "200", description = "Sesión abierta.")
    @ApiResponse(responseCode = "401", description = "`invalid-credentials`: email o contraseña incorrectos.")
    @ApiResponse(responseCode = "429", description = ApiDoc.RATE_LIMITED,
            headers = @Header(name = RETRY_AFTER, description = RETRY_AFTER_DOC, schema = @Schema(type = "integer")))
    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        rateLimiter.checkLogin(http.getRemoteAddr(), request.email());
        return authService.login(request);
    }

    @Operation(summary = "Iniciar sesión con Google",
            description = "Valida el ID token de Google Sign-In (firma con las claves públicas de Google, emisor, "
                    + "audiencia y caducidad). Si el `sub` de Google ya está vinculado entra en esa cuenta; si no, "
                    + "un email verificado igual al de una cuenta existente se vincula a ella; si no existe, se crea "
                    + "un usuario sin contraseña. Al vincular una cuenta cuyo email no estaba verificado se elimina "
                    + "su contraseña y se cierran sus sesiones (D-021). Rate limiting por IP.")
    @ApiResponse(responseCode = "200", description = "Sesión abierta.")
    @ApiResponse(responseCode = "401", description = "`invalid-google-token`: el ID token no es válido, ha caducado "
            + "o no es para esta aplicación." + ApiDoc.NL
            + "`google-email-not-verified`: Google no ha verificado el email de esa cuenta.")
    @ApiResponse(responseCode = "429", description = ApiDoc.RATE_LIMITED,
            headers = @Header(name = RETRY_AFTER, description = RETRY_AFTER_DOC, schema = @Schema(type = "integer")))
    @ApiResponse(responseCode = "503", description = "`google-unavailable`: no se pudieron obtener las claves "
            + "públicas de Google; reintenta más tarde.")
    @PostMapping("/google")
    AuthResponse google(@Valid @RequestBody GoogleLoginRequest request, HttpServletRequest http) {
        rateLimiter.checkGoogle(http.getRemoteAddr());
        return authService.google(request);
    }

    @Operation(summary = "Renovar el access token",
            description = "Canjea un refresh token por un par nuevo. Es de un solo uso (rotatorio): presentar uno "
                    + "ya canjeado se trata como robo y revoca toda la familia de tokens de esa sesión.")
    @ApiResponse(responseCode = "200", description = "Par de tokens nuevo.")
    @ApiResponse(responseCode = "401", description = "`invalid-refresh-token`: desconocido, caducado, revocado "
            + "o reutilizado.")
    @PostMapping("/refresh")
    AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @Operation(summary = "Cerrar sesión",
            description = "Revoca el refresh token indicado (y con él la sesión). Idempotente: responde 204 aunque "
                    + "el token no exista o ya esté revocado, para no revelar nada.")
    @ApiResponse(responseCode = "204", description = "Sesión cerrada (o ya lo estaba).")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
    }
}
