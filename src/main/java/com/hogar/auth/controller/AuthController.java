package com.hogar.auth.controller;

import com.hogar.auth.dto.AuthResponse;
import com.hogar.auth.dto.GoogleLoginRequest;
import com.hogar.auth.dto.LoginRequest;
import com.hogar.auth.dto.RefreshTokenRequest;
import com.hogar.auth.dto.RegisterRequest;
import com.hogar.auth.service.AuthRateLimiter;
import com.hogar.auth.service.AuthService;
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
class AuthController {

    private static final URI ME = URI.create("/api/users/me");

    private final AuthService authService;
    private final AuthRateLimiter rateLimiter;

    AuthController(AuthService authService, AuthRateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register")
    ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        rateLimiter.checkRegister(http.getRemoteAddr(), request.email());
        return ResponseEntity.created(ME).body(authService.register(request));
    }

    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        rateLimiter.checkLogin(http.getRemoteAddr(), request.email());
        return authService.login(request);
    }

    @PostMapping("/google")
    AuthResponse google(@Valid @RequestBody GoogleLoginRequest request, HttpServletRequest http) {
        rateLimiter.checkGoogle(http.getRemoteAddr());
        return authService.google(request);
    }

    @PostMapping("/refresh")
    AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
    }
}
