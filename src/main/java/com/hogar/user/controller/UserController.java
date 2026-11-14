package com.hogar.user.controller;

import com.hogar.shared.config.ApiDoc;
import com.hogar.shared.error.UnauthorizedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.hogar.shared.security.CurrentUser;
import com.hogar.user.dto.UserResponse;
import com.hogar.user.service.UserAccounts;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@Tag(name = ApiDoc.TAG_USERS)
class UserController {

    private final UserAccounts userAccounts;
    private final CurrentUser currentUser;

    UserController(UserAccounts userAccounts, CurrentUser currentUser) {
        this.userAccounts = userAccounts;
        this.currentUser = currentUser;
    }

    /** Perfil del usuario autenticado. Un token válido de un usuario ya inexistente → 401. */
    @Operation(summary = "Consultar mi perfil",
            description = "Datos del usuario del access token: email, nombre, si tiene contraseña y qué "
                    + "proveedores externos tiene vinculados.")
    @ApiResponse(responseCode = "200", description = "Perfil del usuario autenticado.")
    @ApiResponse(responseCode = "401", description = ApiDoc.UNAUTHORIZED + ApiDoc.NL
            + "`unauthorized`: el token es válido pero el usuario ya no existe.")
    @GetMapping("/me")
    UserResponse me() {
        return userAccounts.findProfile(currentUser.id())
                .orElseThrow(() -> new UnauthorizedException("unauthorized", "Se requiere autenticación."));
    }
}
