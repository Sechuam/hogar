package com.hogar.household.controller;

import com.hogar.household.dto.MemberResponse;
import com.hogar.household.dto.UpdateMemberRoleRequest;
import com.hogar.household.service.MemberService;
import com.hogar.shared.config.ApiDoc;
import com.hogar.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/households/{householdId}")
@Tag(name = ApiDoc.TAG_MEMBERS)
class MemberController {

    private final MemberService members;
    private final CurrentUser currentUser;

    MemberController(MemberService members, CurrentUser currentUser) {
        this.members = members;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Listar los miembros activos",
            description = "Ordenados por fecha de alta. Sin paginar: el tamaño de un hogar es pequeño por naturaleza.")
    @ApiResponse(responseCode = "200", description = "Miembros activos del hogar.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @GetMapping("/members")
    List<MemberResponse> list(@Parameter(description = "ID del hogar") @PathVariable UUID householdId) {
        return members.list(householdId, currentUser.id());
    }

    @Operation(summary = "Cambiar el rol de un miembro (OWNER)",
            description = "Puede haber varios OWNER. No se permite dejar el hogar sin ningún OWNER.")
    @ApiResponse(responseCode = "200", description = "Miembro con el rol actualizado.")
    @ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
    @ApiResponse(responseCode = "403", description = ApiDoc.OWNER_ROLE_REQUIRED)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.MEMBER_NOT_FOUND)
    @ApiResponse(responseCode = "422", description = "`last-owner`: el cambio dejaría el hogar sin ningún OWNER.")
    @PatchMapping("/members/{userId}")
    MemberResponse changeRole(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID del usuario miembro") @PathVariable UUID userId,
                              @Valid @RequestBody UpdateMemberRoleRequest request) {
        return members.changeRole(householdId, currentUser.id(), userId, request.role());
    }

    @Operation(summary = "Expulsar a un miembro (OWNER)",
            description = "La membresía no se borra: se cierra y queda registrado quién expulsó. Sus gastos, "
                    + "tareas y saldo siguen en el histórico. Para salir uno mismo, `POST /leave`.")
    @ApiResponse(responseCode = "204", description = "Miembro expulsado.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "403", description = ApiDoc.OWNER_ROLE_REQUIRED)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.MEMBER_NOT_FOUND)
    @ApiResponse(responseCode = "422", description = "`use-leave-endpoint`: no puedes expulsarte a ti mismo; "
            + "usa `POST /leave`.")
    @DeleteMapping("/members/{userId}")
    ResponseEntity<Void> remove(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID del usuario miembro") @PathVariable UUID userId) {
        members.remove(householdId, currentUser.id(), userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Abandonar el hogar",
            description = "El último OWNER no puede salir mientras queden otros miembros (debe ceder el rol antes). "
                    + "Si es el único miembro, puede salir y el hogar queda sin miembros activos. Salir con saldo "
                    + "pendiente está permitido: sigue apareciendo en los balances.")
    @ApiResponse(responseCode = "204", description = "Has salido del hogar.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @ApiResponse(responseCode = "422", description = "`last-owner`: eres el último OWNER y quedan más miembros.")
    @PostMapping("/leave")
    ResponseEntity<Void> leave(@Parameter(description = "ID del hogar") @PathVariable UUID householdId) {
        members.leave(householdId, currentUser.id());
        return ResponseEntity.noContent().build();
    }
}
