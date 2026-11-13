package com.hogar.user.controller;

import com.hogar.shared.error.UnauthorizedException;
import com.hogar.shared.security.CurrentUser;
import com.hogar.user.dto.UserResponse;
import com.hogar.user.service.UserAccounts;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
class UserController {

    private final UserAccounts userAccounts;
    private final CurrentUser currentUser;

    UserController(UserAccounts userAccounts, CurrentUser currentUser) {
        this.userAccounts = userAccounts;
        this.currentUser = currentUser;
    }

    /** Perfil del usuario autenticado. Un token válido de un usuario ya inexistente → 401. */
    @GetMapping("/me")
    UserResponse me() {
        return userAccounts.findProfile(currentUser.id())
                .orElseThrow(() -> new UnauthorizedException("unauthorized", "Se requiere autenticación."));
    }
}
