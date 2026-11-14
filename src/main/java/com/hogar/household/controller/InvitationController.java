package com.hogar.household.controller;

import com.hogar.household.dto.CreateInvitationRequest;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.InvitationResponse;
import com.hogar.household.service.InvitationService;
import com.hogar.shared.config.ApiDoc;
import com.hogar.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@Tag(name = ApiDoc.TAG_INVITATIONS)
class InvitationController {

    private final InvitationService invitations;
    private final CurrentUser currentUser;

    InvitationController(InvitationService invitations, CurrentUser currentUser) {
        this.invitations = invitations;
        this.currentUser = currentUser;
    }

    /** El cuerpo es opcional: sin él se usan el TTL por defecto y usos ilimitados. */
    @Operation(summary = "Crear una invitación (OWNER)",
            description = "Genera un código aleatorio de 10 caracteres (Crockford Base32) para compartir. El cuerpo "
                    + "es opcional: sin él se usan la caducidad por defecto y usos ilimitados.")
    @ApiResponse(responseCode = "201", description = "Invitación creada.",
            headers = @Header(name = "Location", description = "URL de la invitación", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
    @ApiResponse(responseCode = "403", description = ApiDoc.OWNER_ROLE_REQUIRED)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @PostMapping("/api/households/{householdId}/invitations")
    ResponseEntity<InvitationResponse> create(@Parameter(description = "ID del hogar") @PathVariable UUID householdId,
                                              @Valid @RequestBody(required = false) CreateInvitationRequest request) {
        CreateInvitationRequest body = request != null ? request : new CreateInvitationRequest(null, null);
        InvitationResponse created = invitations.create(householdId, currentUser.id(), body);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Listar las invitaciones del hogar (OWNER)",
            description = "Las más recientes primero: todas las vigentes (hasta 100) y las 20 revocadas o caducadas "
                    + "más recientes.")
    @ApiResponse(responseCode = "200", description = "Invitaciones del hogar.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "403", description = ApiDoc.OWNER_ROLE_REQUIRED)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @GetMapping("/api/households/{householdId}/invitations")
    List<InvitationResponse> list(@Parameter(description = "ID del hogar") @PathVariable UUID householdId) {
        return invitations.list(householdId, currentUser.id());
    }

    @Operation(summary = "Ver una invitación (OWNER)",
            description = "Incluye las revocadas, caducadas o agotadas. Es el destino del `Location` del alta.")
    @ApiResponse(responseCode = "200", description = "Invitación.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "403", description = ApiDoc.OWNER_ROLE_REQUIRED)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL
            + "`invitation-not-found`: la invitación no es de este hogar.")
    @GetMapping("/api/households/{householdId}/invitations/{invitationId}")
    InvitationResponse get(@Parameter(description = "ID del hogar") @PathVariable UUID householdId,
                           @Parameter(description = "ID de la invitación") @PathVariable UUID invitationId) {
        return invitations.get(householdId, currentUser.id(), invitationId);
    }

    @Operation(summary = "Revocar una invitación (OWNER)",
            description = "Idempotente: revocar una ya revocada también responde 204.")
    @ApiResponse(responseCode = "204", description = "Invitación revocada.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "403", description = ApiDoc.OWNER_ROLE_REQUIRED)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL
            + "`invitation-not-found`: la invitación no es de este hogar.")
    @DeleteMapping("/api/households/{householdId}/invitations/{invitationId}")
    ResponseEntity<Void> revoke(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID de la invitación") @PathVariable UUID invitationId) {
        invitations.revoke(householdId, currentUser.id(), invitationId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Aceptar una invitación",
            description = "Une al usuario autenticado al hogar como MEMBER. El código se normaliza (mayúsculas, sin "
                    + "guiones, O→0, I/L→1), así que admite errores de transcripción habituales.")
    @ApiResponse(responseCode = "200", description = "Hogar al que te has unido.")
    @ApiResponse(responseCode = "403", description = "`removed-from-household`: te expulsaron del hogar después de "
            + "crearse esta invitación (D-028).")
    @ApiResponse(responseCode = "404", description = "`invitation-not-found`: el código no existe, está revocado o el "
            + "hogar ya no tiene miembros.")
    @ApiResponse(responseCode = "409", description = "`already-member`: ya eres miembro activo de ese hogar.")
    @ApiResponse(responseCode = "422", description = "`invitation-expired`: la invitación ha caducado." + ApiDoc.NL
            + "`invitation-exhausted`: se agotaron sus usos.")
    @PostMapping("/api/invitations/{code}/accept")
    HouseholdResponse accept(@Parameter(description = "Código de invitación", example = "K7M2-Q9XW-4P") @PathVariable String code) {
        return invitations.accept(code, currentUser.id());
    }
}
