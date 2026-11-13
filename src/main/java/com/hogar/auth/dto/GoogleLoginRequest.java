package com.hogar.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** ID token de Google obtenido por el cliente móvil (Google Sign-In). */
public record GoogleLoginRequest(@NotBlank @Size(max = 4096) String idToken) {
}
