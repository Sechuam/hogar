package com.hogar.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** ID token de Google obtenido por el cliente móvil (Google Sign-In). */
public record GoogleLoginRequest(@Schema(description = "ID token de Google Sign-In (JWT firmado por Google).", example = "eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ.eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20ifQ.firma") @NotBlank @Size(max = 4096) String idToken) {
}
